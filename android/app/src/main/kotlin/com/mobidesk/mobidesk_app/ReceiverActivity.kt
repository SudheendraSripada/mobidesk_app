package com.mobidesk.mobidesk_app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Native full-screen Activity on Phone B (Receiver / Simulated Dock).
 *
 * Implements safe lifecycle handling and low-latency decoding:
 * 1. Opaque solid black window and hardware SurfaceView to eliminate transparency bugs across OEM devices.
 * 2. Manages UsbHostReceiver with isolated background handshake and runtime permissions.
 * 3. Low-latency MediaCodec decoder configuration (KEY_LOW_LATENCY = 1, KEY_PRIORITY = 0, flush on start).
 * 4. Zero-queue frame feeding: drops non-IDR frames when input queue is saturated to eliminate buffer bloat.
 * 5. Instant rendering on arrival via releaseOutputBuffer(index, true).
 * 6. Sleep state overlay: displays "phone in sleep wake up to view" when Phone A screen is off.
 */
class ReceiverActivity : Activity() {

    companion object {
        private const val TAG = "ReceiverActivity"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        private const val TIMEOUT_USEC = 10_000L
        private const val DEFAULT_DECODER_WIDTH = 720
        private const val DEFAULT_DECODER_HEIGHT = 1280
        const val INACTIVITY_WATCHDOG_TIMEOUT_MS = 3500L
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusOverlay: FrameLayout
    private lateinit var statusTextView: TextView
    private lateinit var progressBar: ProgressBar

    // Dedicated overlay for sleep / screen-off state
    private lateinit var sleepOverlay: FrameLayout
    private lateinit var sleepTextView: TextView

    private var mediaCodec: MediaCodec? = null
    private var usbHostReceiver: UsbHostReceiver? = null
    private var drainThread: Thread? = null
    private var currentSurface: Surface? = null
    @Volatile
    private var verifiedBulkInEndpoint: UsbEndpoint? = null

    @Volatile
    private var isDecoding = false
    @Volatile
    private var hasReceivedFirstFrame = false
    @Volatile
    private var cachedConfigPacket: ByteArray? = null

    @Volatile
    private var isPhoneSleeping = false
    @Volatile
    private var lastRenderedFrameTimeMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var inactivityWatchdogRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // Keep screen awake with solid black window background for clean video decoding
            window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
                addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            // Create UI layout programmatically: SurfaceView with overlays on top
            rootLayout = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
            }

            surfaceView = SurfaceView(this).apply {
                setZOrderMediaOverlay(false)
                holder.setFormat(PixelFormat.OPAQUE)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            rootLayout.addView(surfaceView)

            // Status overlay showing USB handshake and connection status with solid opaque black background
            statusOverlay = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val overlayContent = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }

            progressBar = ProgressBar(this).apply {
                isIndeterminate = true
            }
            overlayContent.addView(progressBar)

            statusTextView = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(32, 24, 32, 24)
                text = "Connecting to USB AOA Host..."
            }
            overlayContent.addView(statusTextView)
            statusOverlay.addView(overlayContent)
            rootLayout.addView(statusOverlay)

            // Sleep overlay: displayed when Phone A screen turns off or sleeps with solid black background
            sleepOverlay = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val sleepContent = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }

            val sleepIcon = TextView(this).apply {
                text = "💤"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            }
            sleepContent.addView(sleepIcon)

            sleepTextView = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(32, 16, 32, 16)
                text = "phone in sleep wake up to view"
            }
            sleepContent.addView(sleepTextView)
            sleepOverlay.addView(sleepContent)
            rootLayout.addView(sleepOverlay)

            setContentView(rootLayout)

            // Enter native immersive full-screen mode
            applyFullScreen()
            window?.decorView?.post {
                applyFullScreen()
            }

            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.i(TAG, "Surface created, preparing USB receiver pipeline...")
                    val surface = holder.surface
                    currentSurface = surface
                    val inEp = verifiedBulkInEndpoint
                    if (inEp != null && !isDecoding && surface != null && surface.isValid) {
                        initMediaCodecDecoder(surface)
                        usbHostReceiver?.requestKeyframeFromSender()
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    currentSurface = holder.surface
                    Log.i(TAG, "Surface dimension changed: ${width}x$height")
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.i(TAG, "Surface destroyed, releasing decoder...")
                    currentSurface = null
                    stopDecoder()
                }
            })

            // Start receiver pipeline safely inside onCreate try-catch
            startReceiverPipeline()
        } catch (e: Exception) {
            Log.e(TAG, "Safe Lifecycle: Unhandled error in onCreate: ${e.message}", e)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(
                        this,
                        "Receiver error: ${e.message ?: "Initialization failed"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                finish()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent received: action=${intent?.action}")
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            if (usbHostReceiver == null) {
                startReceiverPipeline()
            } else {
                usbHostReceiver?.triggerScan()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isFinishing && !isDestroyed) {
            applyFullScreen()
        }
    }

    internal fun applyFullScreen() {
        try {
            val win = window ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = try {
                    win.decorView.windowInsetsController ?: win.insetsController
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to get WindowInsetsController: ${e.message}")
                    null
                }
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    @Suppress("DEPRECATION")
                    win.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                win.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to apply full screen: ${e.message}")
        }
    }

    private fun updateStatus(text: String, showProgress: Boolean = true) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (!::statusTextView.isInitialized || !::progressBar.isInitialized || !::statusOverlay.isInitialized) {
                return@runOnUiThread
            }
            statusTextView.text = text
            progressBar.visibility = if (showProgress) View.VISIBLE else View.GONE
            if (!hasReceivedFirstFrame) {
                statusOverlay.visibility = View.VISIBLE
            } else {
                statusOverlay.visibility = View.GONE
            }
        }
    }

    private fun onFirstFrameDetected() {
        if (!hasReceivedFirstFrame) {
            hasReceivedFirstFrame = true
            Log.i(TAG, "First video frame rendered. Hiding status overlay.")
            hideOverlay()
        }
        if (isPhoneSleeping) {
            handleSleepState(false)
        }
    }

    private fun hideOverlay() {
        hasReceivedFirstFrame = true
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (::statusOverlay.isInitialized) {
                statusOverlay.visibility = View.GONE
            }
        }
    }

    private fun handleSleepState(isSleep: Boolean) {
        if (isPhoneSleeping == isSleep) return
        isPhoneSleeping = isSleep
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (::sleepOverlay.isInitialized) {
                sleepOverlay.visibility = if (isSleep) View.VISIBLE else View.GONE
            }
        }
    }

    private fun startInactivityWatchdog() {
        stopInactivityWatchdog()
        val watchdog = object : Runnable {
            override fun run() {
                if (!isFinishing && !isDestroyed && isDecoding && hasReceivedFirstFrame) {
                    val now = System.currentTimeMillis()
                    if (now - lastRenderedFrameTimeMs > INACTIVITY_WATCHDOG_TIMEOUT_MS) {
                        handleSleepState(true)
                    }
                }
                mainHandler.postDelayed(this, 1000L)
            }
        }
        inactivityWatchdogRunnable = watchdog
        mainHandler.postDelayed(watchdog, 1000L)
    }

    private fun stopInactivityWatchdog() {
        inactivityWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        inactivityWatchdogRunnable = null
    }

    /**
     * Starts the USB receiver pipeline.
     */
    private fun startReceiverPipeline() {
        stopReceiverPipeline()
        try {
            val manager = getSystemService(Context.USB_SERVICE) as? UsbManager
            val receiver = UsbHostReceiver(
                context = this,
                usbManager = manager,
                onFrameReceived = { type, flags, ptsUs, payload ->
                    feedDecoder(type, flags, ptsUs, payload)
                }
            ).apply {
                onBulkInReady = { inEp ->
                    Log.i(TAG, "Bulk IN endpoint verified (${inEp.address}); initializing MediaCodec decoder...")
                    verifiedBulkInEndpoint = inEp
                    val surface = currentSurface
                    if (surface != null && surface.isValid) {
                        initMediaCodecDecoder(surface)
                        usbHostReceiver?.requestKeyframeFromSender()
                    } else {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            val surf = currentSurface
                            if (surf != null && surf.isValid) {
                                initMediaCodecDecoder(surf)
                                usbHostReceiver?.requestKeyframeFromSender()
                            } else {
                                Log.w(TAG, "Surface is not yet ready when Bulk IN endpoint was verified")
                            }
                        }
                    }
                }

                onStatusChanged = { msg ->
                    updateStatus(msg, true)
                }

                onError = { err ->
                    Log.w(TAG, "USB receiver error: $err")
                    updateStatus(err, false)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this@ReceiverActivity, err, Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                onConnected = {
                    if (hasReceivedFirstFrame) {
                        hideOverlay()
                    } else {
                        updateStatus("Connected! Streaming video...", false)
                    }
                }

                onDisconnected = {
                    hasReceivedFirstFrame = false
                    verifiedBulkInEndpoint = null
                    stopInactivityWatchdog()
                    handleSleepState(false)
                    stopDecoder()
                    updateStatus("USB Accessory disconnected. Reconnecting...", true)
                }
            }

            usbHostReceiver = receiver
            receiver.start()
            startInactivityWatchdog()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UsbHostReceiver: ${e.message}", e)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, "Receiver connection error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }
    }

    /**
     * Initializes MediaCodec hardware video/avc decoder with low-latency configuration.
     */
    @Synchronized
    private fun initMediaCodecDecoder(surface: Surface) {
        if (isDecoding) return
        if (!surface.isValid) {
            Log.w(TAG, "Cannot initialize MediaCodec decoder: Surface is not valid")
            return
        }
        if (isFinishing || isDestroyed) return
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                DEFAULT_DECODER_WIDTH,
                DEFAULT_DECODER_HEIGHT
            ).apply {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    } catch (_: Exception) {}
                }
            }

            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surface, null, 0)
            codec.start()

            // Flush decoder on startup to discard any driver-stale frame state
            try {
                codec.flush()
            } catch (_: Exception) {}

            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            mediaCodec = codec
            isDecoding = true
            Log.i(TAG, "MediaCodec video/avc decoder successfully configured, flushed, and bound to Surface.")

            cachedConfigPacket?.let { config ->
                feedDecoder(FramingProtocol.TYPE_CONFIG, FramingProtocol.FLAG_KEYFRAME, 0L, config)
            }

            drainThread = thread(name = "ReceiverDrainThread") {
                drainDecoder()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaCodec decoder: ${e.message}", e)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, "Decoder error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }
    }

    /**
     * Checks if the Annex-B payload starts with SPS (NAL type 7).
     */
    internal fun hasSpsPrefix(data: ByteArray): Boolean {
        if (data.size < 5) return false
        if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            val nalType = data[4].toInt() and 0x1F
            return nalType == 7
        }
        if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            val nalType = data[3].toInt() and 0x1F
            return nalType == 7
        }
        return false
    }

    /**
     * Feeds incoming demuxed frames into MediaCodec input buffers using zero-queue drop logic.
     */
    private fun feedDecoder(type: Byte, flags: Byte, ptsUs: Long, payload: ByteArray) {
        if (type == FramingProtocol.TYPE_HEARTBEAT) {
            return
        }

        if (type == FramingProtocol.TYPE_SLEEP) {
            val isSleep = payload.isNotEmpty() && payload[0] == 1.toByte()
            Log.i(TAG, "Received sleep state packet: isSleep=$isSleep")
            handleSleepState(isSleep)
            return
        }

        // Cache configuration bytes (SPS/PPS) immediately
        if (type == FramingProtocol.TYPE_CONFIG) {
            cachedConfigPacket = payload.copyOf()
            Log.i(TAG, "Cached SPS/PPS codec config packet (${payload.size} bytes)")
        }

        if (type == FramingProtocol.TYPE_FRAME) {
            if (isPhoneSleeping) {
                handleSleepState(false)
            }
        }

        val codec = mediaCodec ?: return
        if (!isDecoding) return

        try {
            val isKeyframe = (flags.toInt() and FramingProtocol.FLAG_KEYFRAME.toInt()) != 0
            val isConfig = type == FramingProtocol.TYPE_CONFIG

            // Zero-Queue Architecture: Non-blocking dequeue of input buffer
            var inputBufferIndex = codec.dequeueInputBuffer(0)
            if (inputBufferIndex < 0) {
                if (!isKeyframe && !isConfig) {
                    // Decoder input queue is full: drop non-IDR frame immediately rather than buffering
                    return
                }
                // For IDR sync / config frames, retry up to 5 times (20ms each) to prevent keyframe drops on slower chipsets
                var retries = 0
                while (isDecoding && inputBufferIndex < 0 && retries < 5) {
                    inputBufferIndex = codec.dequeueInputBuffer(20_000L)
                    retries++
                }
                if (inputBufferIndex < 0) {
                    Log.w(TAG, "Decoder input queue full: dropped keyframe/config after retries")
                    return
                }
            }

            val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
            inputBuffer.clear()

            val cachedConfig = cachedConfigPacket

            // If keyframe and cachedConfig exists, ensure SPS/PPS is prepended if not already present
            val finalPayload = if (type == FramingProtocol.TYPE_FRAME && isKeyframe && cachedConfig != null && !hasSpsPrefix(payload)) {
                val combined = ByteArray(cachedConfig.size + payload.size)
                System.arraycopy(cachedConfig, 0, combined, 0, cachedConfig.size)
                System.arraycopy(payload, 0, combined, cachedConfig.size, payload.size)
                combined
            } else {
                payload
            }

            val toCopy = minOf(finalPayload.size, inputBuffer.remaining())
            if (toCopy < finalPayload.size) {
                Log.w(TAG, "Payload size (${finalPayload.size}) exceeds inputBuffer capacity (${inputBuffer.remaining()})")
            }
            inputBuffer.put(finalPayload, 0, toCopy)

            val bufferFlags = when {
                isConfig -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                isKeyframe -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                else -> 0
            }

            codec.queueInputBuffer(inputBufferIndex, 0, toCopy, ptsUs, bufferFlags)
        } catch (e: Exception) {
            if (isDecoding) {
                Log.e(TAG, "Error queuing buffer to MediaCodec: ${e.message}")
            }
        }
    }

    /**
     * Continuously dequeues decoded frames and renders them immediately to the SurfaceView.
     */
    private fun drainDecoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isDecoding) {
            val codec = mediaCodec ?: break
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                if (outputBufferIndex >= 0) {
                    // MUST be true to render directly onto the Surface immediately on arrival
                    codec.releaseOutputBuffer(outputBufferIndex, true)
                    lastRenderedFrameTimeMs = System.currentTimeMillis()
                    onFirstFrameDetected()
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "Decoder output format changed: $newFormat")
                    val cropLeft = if (newFormat.containsKey("crop-left")) newFormat.getInteger("crop-left") else 0
                    val cropRight = if (newFormat.containsKey("crop-right")) newFormat.getInteger("crop-right") else 0
                    val cropTop = if (newFormat.containsKey("crop-top")) newFormat.getInteger("crop-top") else 0
                    val cropBottom = if (newFormat.containsKey("crop-bottom")) newFormat.getInteger("crop-bottom") else 0

                    val videoWidth = if (cropRight > cropLeft) cropRight - cropLeft + 1
                        else if (newFormat.containsKey(MediaFormat.KEY_WIDTH)) newFormat.getInteger(MediaFormat.KEY_WIDTH) else 0
                    val videoHeight = if (cropBottom > cropTop) cropBottom - cropTop + 1
                        else if (newFormat.containsKey(MediaFormat.KEY_HEIGHT)) newFormat.getInteger(MediaFormat.KEY_HEIGHT) else 0

                    if (videoWidth > 0 && videoHeight > 0) {
                        runOnUiThread {
                            adjustSurfaceAspectRatio(videoWidth, videoHeight)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isDecoding) {
                    Log.e(TAG, "Error draining MediaCodec: ${e.message}")
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        break
                    }
                } else {
                    break
                }
            }
        }
    }

    internal fun adjustSurfaceAspectRatio(videoWidth: Int, videoHeight: Int) {
        try {
            if (isFinishing || isDestroyed) return
            if (!::surfaceView.isInitialized || !::rootLayout.isInitialized) {
                return
            }
            surfaceView.holder.setFixedSize(videoWidth, videoHeight)
            val rootW = rootLayout.width
            val rootH = rootLayout.height
            if (rootW > 0 && rootH > 0) {
                val videoRatio = videoWidth.toDouble() / videoHeight
                val screenRatio = rootW.toDouble() / rootH

                val lp = (surfaceView.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(rootW, rootH)
                if (videoRatio > screenRatio) {
                    lp.width = rootW
                    lp.height = (rootW / videoRatio).toInt()
                } else {
                    lp.height = rootH
                    lp.width = (rootH * videoRatio).toInt()
                }
                lp.gravity = Gravity.CENTER
                surfaceView.layoutParams = lp
            } else {
                rootLayout.post {
                    adjustSurfaceAspectRatio(videoWidth, videoHeight)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error adjusting aspect ratio: ${e.message}")
        }
    }

    @Synchronized
    private fun stopDecoder() {
        if (!isDecoding && mediaCodec == null) return
        isDecoding = false
        hasReceivedFirstFrame = false

        if (Thread.currentThread() !== drainThread) {
            try {
                drainThread?.join(1000)
            } catch (_: InterruptedException) {}
        }
        drainThread = null

        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaCodec: ${e.message}")
        }
        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaCodec: ${e.message}")
        }
        mediaCodec = null
        Log.i(TAG, "MediaCodec decoder stopped and released.")
    }

    @Synchronized
    private fun stopReceiverPipeline() {
        stopInactivityWatchdog()
        handleSleepState(false)
        stopDecoder()

        try {
            usbHostReceiver?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping UsbHostReceiver: ${e.message}")
        }
        usbHostReceiver = null

        Log.i(TAG, "Receiver pipeline stopped.")
    }

    override fun onDestroy() {
        stopReceiverPipeline()
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
