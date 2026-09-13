package com.mobidesk.mobidesk_app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
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
import kotlin.concurrent.thread

/**
 * Native full-screen Activity on Phone B (Receiver / Simulated Dock).
 *
 * Implements safe lifecycle handling:
 * 1. Wraps all setup in onCreate() inside a try-catch block. On error, displays a Toast and finish()es gracefully.
 * 2. Manages UsbHostReceiver with isolated background handshake and runtime permissions.
 * 3. Only initializes MediaCodec decoder and binds Surface once the Bulk IN endpoint is successfully opened and verified.
 */
class ReceiverActivity : Activity() {

    companion object {
        private const val TAG = "ReceiverActivity"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        private const val TIMEOUT_USEC = 10_000L
        private const val DEFAULT_DECODER_WIDTH = 720
        private const val DEFAULT_DECODER_HEIGHT = 1280
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusOverlay: FrameLayout
    private lateinit var statusTextView: TextView
    private lateinit var progressBar: ProgressBar

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // Keep screen awake while receiver is active
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Enter native immersive full-screen mode
            applyFullScreen()

            // Create UI layout programmatically: SurfaceView with a status overlay on top
            rootLayout = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
            }

            surfaceView = SurfaceView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            rootLayout.addView(surfaceView)

            // Status overlay showing USB handshake and connection status
            statusOverlay = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#99000000"))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val overlayContent = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
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
                setPadding(32, 24, 32, 24)
                text = "Connecting to USB AOA Host..."
            }
            overlayContent.addView(statusTextView)

            statusOverlay.addView(overlayContent)
            rootLayout.addView(statusOverlay)

            setContentView(rootLayout)

            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.i(TAG, "Surface created, preparing USB receiver pipeline...")
                    currentSurface = holder.surface
                    val inEp = verifiedBulkInEndpoint
                    if (inEp != null && !isDecoding) {
                        initMediaCodecDecoder(holder.surface)
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

            // Requirement 3: Start receiver pipeline safely inside onCreate try-catch
            startReceiverPipeline()
        } catch (e: Exception) {
            // Safe Lifecycle: Display an Android Toast on UI thread and finish() gracefully back to Flutter
            Log.e(TAG, "Safe Lifecycle: Unhandled error in onCreate: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Receiver error: ${e.message ?: "Initialization failed"}",
                    Toast.LENGTH_LONG
                ).show()
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
        if (hasFocus) {
            applyFullScreen()
        }
    }

    private fun applyFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    private fun updateStatus(text: String, showProgress: Boolean = true) {
        runOnUiThread {
            statusTextView.text = text
            progressBar.visibility = if (showProgress) View.VISIBLE else View.GONE
            if (!hasReceivedFirstFrame) {
                statusOverlay.visibility = View.VISIBLE
            }
        }
    }

    private fun hideOverlay() {
        runOnUiThread {
            statusOverlay.visibility = View.GONE
        }
    }

    /**
     * Starts the USB receiver pipeline.
     * Note: Does NOT initialize MediaCodec yet. MediaCodec is ONLY initialized once
     * the Bulk IN endpoint is successfully opened and verified (Requirement 4).
     */
    private fun startReceiverPipeline() {
        try {
            val manager = getSystemService(Context.USB_SERVICE) as? UsbManager
            val receiver = UsbHostReceiver(
                context = this,
                usbManager = manager,
                onFrameReceived = { type, flags, ptsUs, payload ->
                    feedDecoder(type, flags, ptsUs, payload)
                }
            ).apply {
                // Requirement 4: Only initialize the MediaCodec decoder and bind the Surface
                // once the Bulk IN endpoint is successfully opened and verified.
                onBulkInReady = { inEp ->
                    Log.i(TAG, "Bulk IN endpoint verified (${inEp.address}); initializing MediaCodec decoder and binding Surface...")
                    verifiedBulkInEndpoint = inEp
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val surface = currentSurface
                        if (surface != null && surface.isValid) {
                            initMediaCodecDecoder(surface)
                            usbHostReceiver?.requestKeyframeFromSender()
                        } else {
                            Log.w(TAG, "Surface is not yet ready when Bulk IN endpoint was verified")
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
                    updateStatus("Connected! Streaming video...", false)
                }

                onDisconnected = {
                    hasReceivedFirstFrame = false
                    verifiedBulkInEndpoint = null
                    stopDecoder()
                    updateStatus("USB Accessory disconnected. Reconnecting...", true)
                }
            }

            usbHostReceiver = receiver
            receiver.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UsbHostReceiver: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Receiver connection error: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * Initializes MediaCodec hardware video/avc decoder and binds it to the Surface.
     * Guaranteed to be called ONLY after Bulk IN endpoint is verified.
     */
    @Synchronized
    private fun initMediaCodecDecoder(surface: Surface) {
        if (isDecoding) return
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
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            mediaCodec = codec
            isDecoding = true
            hasReceivedFirstFrame = false
            Log.i(TAG, "MediaCodec video/avc decoder successfully configured and bound to Surface.")

            drainThread = thread(name = "ReceiverDrainThread") {
                drainDecoder()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaCodec decoder: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Decoder error: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * Feeds incoming demuxed frames into MediaCodec input buffers.
     */
    private fun feedDecoder(type: Byte, flags: Byte, ptsUs: Long, payload: ByteArray) {
        if (type == FramingProtocol.TYPE_HEARTBEAT) {
            // Heartbeat packet, do not feed into video decoder
            return
        }
        val codec = mediaCodec ?: return
        if (!isDecoding) return

        try {
            var inputBufferIndex = -1
            val maxRetries = if (type == FramingProtocol.TYPE_CONFIG) 10 else 3
            var attempt = 0
            while (isDecoding && inputBufferIndex < 0 && attempt < maxRetries) {
                inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputBufferIndex < 0) {
                    attempt++
                }
            }

            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
                inputBuffer.clear()
                val toCopy = minOf(payload.size, inputBuffer.remaining())
                inputBuffer.put(payload, 0, toCopy)

                val bufferFlags = when (type) {
                    FramingProtocol.TYPE_CONFIG -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    else -> if ((flags.toInt() and FramingProtocol.FLAG_KEYFRAME.toInt()) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                }

                codec.queueInputBuffer(inputBufferIndex, 0, toCopy, ptsUs, bufferFlags)
            }
        } catch (e: Exception) {
            if (isDecoding) {
                Log.e(TAG, "Error queuing buffer to MediaCodec: ${e.message}")
            }
        }
    }

    /**
     * Continuously dequeues decoded frames and renders them to the SurfaceView.
     */
    private fun drainDecoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isDecoding) {
            val codec = mediaCodec ?: break
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                if (outputBufferIndex >= 0) {
                    codec.releaseOutputBuffer(outputBufferIndex, true)

                    if (!hasReceivedFirstFrame) {
                        hasReceivedFirstFrame = true
                        hideOverlay()
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "Decoder output format changed: $newFormat")
                    val videoWidth = if (newFormat.containsKey(MediaFormat.KEY_WIDTH)) newFormat.getInteger(MediaFormat.KEY_WIDTH) else 0
                    val videoHeight = if (newFormat.containsKey(MediaFormat.KEY_HEIGHT)) newFormat.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    if (videoWidth > 0 && videoHeight > 0) {
                        runOnUiThread {
                            adjustSurfaceAspectRatio(videoWidth, videoHeight)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isDecoding) {
                    Log.e(TAG, "Error draining MediaCodec: ${e.message}")
                }
                break
            }
        }
    }

    private fun adjustSurfaceAspectRatio(videoWidth: Int, videoHeight: Int) {
        try {
            surfaceView.holder.setFixedSize(videoWidth, videoHeight)
            val rootW = rootLayout.width
            val rootH = rootLayout.height
            if (rootW > 0 && rootH > 0) {
                val videoRatio = videoWidth.toDouble() / videoHeight
                val screenRatio = rootW.toDouble() / rootH

                val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
                if (videoRatio > screenRatio) {
                    lp.width = rootW
                    lp.height = (rootW / videoRatio).toInt()
                } else {
                    lp.height = rootH
                    lp.width = (rootH * videoRatio).toInt()
                }
                lp.gravity = Gravity.CENTER
                surfaceView.layoutParams = lp
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error adjusting aspect ratio: ${e.message}")
        }
    }

    @Synchronized
    private fun stopDecoder() {
        if (!isDecoding && mediaCodec == null) return
        isDecoding = false

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
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
