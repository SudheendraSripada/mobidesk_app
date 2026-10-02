package com.mobidesk.mobidesk_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Android Foreground Service managing Phone A (Sender):
 * - Maintains a persistent Partial WakeLock to prevent CPU throttling during continuous streaming.
 * - Manages MediaProjection session conforming to Android 14 requirements.
 * - Encodes screen frames using hardware MediaCodec (video/avc H.264 Annex-B NAL units, real-time CBR).
 * - Implements zero-buffering architecture via UsbAccessorySender.
 * - Monitors screen sleep/wake state and dispatches sleep control packets to Phone B.
 * - Streams binary frames over UsbAccessory FileOutputStream via AoaAccessoryManager.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val ACTION_START = "com.mobidesk.action.START"
        const val ACTION_STOP = "com.mobidesk.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_STREAM_MODE = "extra_stream_mode"
        const val STREAM_MODE_MIRROR = "mode_mirror"
        const val STREAM_MODE_VIRTUAL_DISPLAY = "mode_virtual_display"
        const val EXTRA_GUAC_URL = "extra_guac_url"

        private const val NOTIFICATION_CHANNEL_ID = "mobidesk_screen_capture"
        private const val NOTIFICATION_ID = 1001

        const val DEFAULT_WIDTH = 720
        const val DEFAULT_HEIGHT = 1280
        const val DEFAULT_FRAME_RATE = 30
        const val DEFAULT_BIT_RATE = 4_000_000 // 4 Mbps
        const val DEFAULT_I_FRAME_INTERVAL = 1 // 1 second
        private const val TIMEOUT_USEC = 10_000L

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var isFallbackActive: Boolean = false
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var aoaAccessoryManager: AoaAccessoryManager? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var presentation: MobiDeskPresentation? = null
    private var currentStreamMode: String = STREAM_MODE_MIRROR

    @Volatile
    private var cachedCodecConfig: ByteArray? = null

    @Volatile
    private var isStreaming = false
    @Volatile
    private var isStopping = false
    private var drainThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (isStreaming) {
            Log.w(TAG, "ScreenCaptureService already active.")
            return
        }

        // 1. Acquire WakeLock for persistent continuous streaming
        acquireWakeLock()

        // 2. Android 14 requirement: Start foreground service with MEDIA_PROJECTION type BEFORE getMediaProjection
        startForegroundWithNotification()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (resultCode == 0 || dataIntent == null) {
            Log.e(TAG, "Missing resultCode or dataIntent for MediaProjection.")
            handleStop()
            return
        }

        // 3. Obtain MediaProjection token
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, dataIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining MediaProjection: ${e.message}", e)
            null
        }

        if (projection == null) {
            Log.e(TAG, "Failed to obtain MediaProjection.")
            handleStop()
            return
        }
        mediaProjection = projection

        // 4. Android 14 requirement: Register MediaProjection callback before createVirtualDisplay
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.i(TAG, "MediaProjection stopped by system callback.")
                handleStop()
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))

        // 5. Determine display dimensions preserving aspect ratio (720p resolution, 30 FPS)
        val displayMetrics = resources.displayMetrics
        var width = intent.getIntExtra(EXTRA_WIDTH, 0)
        var height = intent.getIntExtra(EXTRA_HEIGHT, 0)
        val dpi = intent.getIntExtra(EXTRA_DPI, displayMetrics.densityDpi)

        if (width <= 0 || height <= 0) {
            val screenW = displayMetrics.widthPixels
            val screenH = displayMetrics.heightPixels
            val isPortrait = screenH >= screenW
            val targetShort = DEFAULT_WIDTH
            val minDim = minOf(screenW, screenH)
            val maxDim = maxOf(screenW, screenH)

            val targetLong = if (minDim > 0) {
                ((targetShort.toDouble() / minDim) * maxDim).toInt()
            } else {
                DEFAULT_HEIGHT
            }

            width = if (isPortrait) targetShort else targetLong
            height = if (isPortrait) targetLong else targetShort
        }

        val streamMode = intent.getStringExtra(EXTRA_STREAM_MODE) ?: STREAM_MODE_MIRROR
        val guacUrl = intent.getStringExtra(EXTRA_GUAC_URL)
        val frameRate = intent.getIntExtra(EXTRA_FPS, DEFAULT_FRAME_RATE)
        currentStreamMode = streamMode
        isFallbackActive = false

        // Ensure even dimensions required by H.264 / AVC video codecs
        if (width % 2 != 0) width--
        if (height % 2 != 0) height--

        // 6. Initialize AoaAccessoryManager for USB Open Accessory bulk transfer
        val accessoryMgr = AoaAccessoryManager(this).apply {
            onAccessoryConnected = {
                Log.i(TAG, "USB Accessory connected to Host. Forcing instant keyframe...")
                cachedCodecConfig?.let { config ->
                    sendConfig(config)
                }
                requestSyncFrame()
            }
            onAccessoryDisconnected = {
                Log.i(TAG, "USB Accessory disconnected from Host. Screen capture remains active.")
            }
            onKeyframeRequested = {
                Log.i(TAG, "Host requested keyframe via AOA channel.")
                cachedCodecConfig?.let { config ->
                    sendConfig(config)
                }
                requestSyncFrame()
            }
        }
        accessoryMgr.start()
        aoaAccessoryManager = accessoryMgr

        // 7. Register broadcast receiver for Screen OFF / Screen ON to notify Phone B of sleep state
        registerScreenStateReceiver()

        // 8. Initialize MediaCodec video encoder for MIME type video/avc (H.264) with strictly real-time low latency
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL)

                // Low-latency Constant Bit Rate (CBR)
                try {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "CBR mode not supported, falling back to VBR: ${e.message}")
                    try {
                        setInteger(
                            MediaFormat.KEY_BITRATE_MODE,
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                        )
                    } catch (_: Exception) {}
                }

                // Real-time low latency and real-time priority (API 26+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        setInteger(MediaFormat.KEY_LATENCY, 0)
                    } catch (_: Exception) {}
                    try {
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                    } catch (_: Exception) {}
                } else {
                    try {
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                    } catch (_: Exception) {}
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    } catch (_: Exception) {}
                }

                // Repeat static frames every 50ms for smooth live output
                setLong(
                    MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                    50_000L
                )
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }
            mediaCodec = codec

            val surface = inputSurface
            if (surface == null) {
                Log.e(TAG, "MediaCodec input surface is null.")
                handleStop()
                return
            }

            // 9. Create VirtualDisplay directing display frames to MediaCodec input surface
            if (streamMode == STREAM_MODE_VIRTUAL_DISPLAY && !guacUrl.isNullOrEmpty()) {
                try {
                    val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    val vDisplay = displayManager.createVirtualDisplay(
                        "MobiDeskMonitorDisplay",
                        width,
                        height,
                        dpi,
                        surface,
                        flags
                    )
                    virtualDisplay = vDisplay

                    Handler(Looper.getMainLooper()).post {
                        try {
                            val pres = MobiDeskPresentation(this@ScreenCaptureService, vDisplay.display, guacUrl)
                            pres.show()
                            presentation = pres
                            Log.i(TAG, "MobiDeskPresentation launched on VirtualDisplay at ${width}x${height} for Guacamole session.")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to instantiate Presentation on VirtualDisplay, activating fallback mirror: ${e.message}", e)
                            fallbackToMirror(projection, width, height, dpi, surface)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create Presentation VirtualDisplay, activating fallback mirror: ${e.message}", e)
                    fallbackToMirror(projection, width, height, dpi, surface)
                }
            } else {
                virtualDisplay = projection.createVirtualDisplay(
                    "MobiDeskCapture",
                    width,
                    height,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
            }

            isStreaming = true
            isServiceRunning = true
            Log.i(TAG, "MediaCodec CBR encoder initialized (${width}x${height} @ ${frameRate} FPS, mode=$streamMode)")

            // Force immediate keyframe on startup/connection if accessory is already connected
            if (aoaAccessoryManager?.isConnected == true) {
                cachedCodecConfig?.let { config ->
                    aoaAccessoryManager?.sendConfig(config)
                }
                requestSyncFrame()
            }

            // 10. In background thread, drain encoded NAL units and stream with zero-queue architecture
            drainThread = thread(name = "ScreenCaptureDrainThread") {
                drainCodec()
            }

            Log.i(TAG, "Screen capture service successfully started and streaming over AOA.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaCodec or VirtualDisplay: ${e.message}", e)
            handleStop()
        }
    }

    private fun fallbackToMirror(
        projection: MediaProjection,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ) {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        try {
            virtualDisplay = projection.createVirtualDisplay(
                "MobiDeskCaptureFallback",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
            isFallbackActive = true
            Log.i(TAG, "Fallback MediaProjection screen mirror successfully activated.")
        } catch (e: Exception) {
            Log.e(TAG, "Fallback MediaProjection mirror also failed: ${e.message}", e)
        }
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (currentStreamMode == STREAM_MODE_VIRTUAL_DISPLAY && !isFallbackActive) {
                            Log.i(TAG, "Phone screen turned OFF. VirtualDisplay monitor streaming remains active.")
                        } else {
                            Log.i(TAG, "Device screen turned OFF. Notifying receiver of sleep state...")
                            aoaAccessoryManager?.sendSleepState(true)
                        }
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        Log.i(TAG, "Device screen turned ON. Notifying receiver of wake state...")
                        aoaAccessoryManager?.sendSleepState(false)
                        requestSyncFrame()
                    }
                }
            }
        }
        registerReceiver(receiver, filter)
        screenStateReceiver = receiver
        Log.i(TAG, "Screen state broadcast receiver registered.")
    }

    private fun unregisterScreenStateReceiver() {
        screenStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering screen state receiver: ${e.message}")
            }
        }
        screenStateReceiver = null
    }

    /**
     * Request an on-demand IDR Sync Frame (Key Frame) from MediaCodec.
     */
    fun requestSyncFrame() {
        try {
            val codec = mediaCodec
            if (codec != null) {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
                codec.setParameters(params)
                Log.i(TAG, "Requested sync frame (keyframe) from MediaCodec")
            } else {
                Log.w(TAG, "Cannot request sync frame: mediaCodec is null")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not request sync frame: ${e.message}")
        }
    }

    /**
     * Reads encoded H.264 Annex-B NAL units and delegates zero-queue transmission
     * to AoaAccessoryManager / UsbAccessorySender.
     */
    private fun drainCodec() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isStreaming) {
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                if (outputBufferIndex >= 0) {
                    val outputBuffer: ByteBuffer? = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val packet = ByteArray(bufferInfo.size)
                        outputBuffer.get(packet)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (isConfig) {
                            // SPS / PPS configuration frame
                            cachedCodecConfig = packet.copyOf()
                            aoaAccessoryManager?.sendConfig(packet)
                            requestSyncFrame()
                        } else {
                            if (isKeyframe && cachedCodecConfig == null) {
                                val extracted = extractSpsPpsFromBuffer(packet)
                                if (extracted != null) {
                                    cachedCodecConfig = extracted
                                    aoaAccessoryManager?.sendConfig(extracted)
                                }
                            }

                            val flags = if (isKeyframe) FramingProtocol.FLAG_KEYFRAME else FramingProtocol.FLAG_NONE

                            // Ensure keyframe has SPS/PPS prepended in-band if not already present
                            val finalPayload = if (isKeyframe && cachedCodecConfig != null && !hasSpsPrefix(packet)) {
                                val cfg = cachedCodecConfig!!
                                val combined = ByteArray(cfg.size + packet.size)
                                System.arraycopy(cfg, 0, combined, 0, cfg.size)
                                System.arraycopy(packet, 0, combined, cfg.size, packet.size)
                                combined
                            } else {
                                packet
                            }

                            aoaAccessoryManager?.sendFrame(
                                FramingProtocol.TYPE_FRAME,
                                flags,
                                bufferInfo.presentationTimeUs,
                                finalPayload,
                                0,
                                finalPayload.size
                            )
                        }
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "MediaCodec output format changed: $newFormat")

                    val csd0 = newFormat.getByteBuffer("csd-0")?.duplicate()
                    val csd1 = newFormat.getByteBuffer("csd-1")?.duplicate()
                    if (csd0 != null && csd1 != null) {
                        val spsBytes = extractAnnexBNal(csd0)
                        val ppsBytes = extractAnnexBNal(csd1)
                        val spsPps = ByteArray(spsBytes.size + ppsBytes.size)
                        System.arraycopy(spsBytes, 0, spsPps, 0, spsBytes.size)
                        System.arraycopy(ppsBytes, 0, spsPps, spsBytes.size, ppsBytes.size)
                        cachedCodecConfig = spsPps
                        aoaAccessoryManager?.sendConfig(spsPps)
                        requestSyncFrame()
                    }
                }
            } catch (e: Exception) {
                if (isStreaming) {
                    Log.e(TAG, "Error draining MediaCodec: ${e.message}")
                }
                break
            }
        }

        if (isStreaming) {
            Log.e(TAG, "Drainage thread terminated unexpectedly, stopping service.")
            handleStop()
        }
    }

    private fun hasSpsPrefix(data: ByteArray): Boolean {
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

    private fun extractAnnexBNal(buffer: ByteBuffer): ByteArray {
        val size = buffer.remaining()
        val pos = buffer.position()
        val hasStartCode = size >= 4 &&
            buffer.get(pos) == 0.toByte() &&
            buffer.get(pos + 1) == 0.toByte() &&
            ((buffer.get(pos + 2) == 1.toByte()) ||
             (buffer.get(pos + 2) == 0.toByte() && buffer.get(pos + 3) == 1.toByte()))

        return if (hasStartCode) {
            val bytes = ByteArray(size)
            buffer.get(bytes)
            bytes
        } else {
            val bytes = ByteArray(size + 4)
            bytes[0] = 0
            bytes[1] = 0
            bytes[2] = 0
            bytes[3] = 1
            buffer.get(bytes, 4, size)
            bytes
        }
    }

    private fun extractSpsPpsFromBuffer(data: ByteArray): ByteArray? {
        var spsStart = -1
        var ppsEnd = -1
        var i = 0
        while (i < data.size - 4) {
            val isStart4 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val isStart3 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            if (isStart4 || isStart3) {
                val headerOffset = if (isStart4) i + 4 else i + 3
                if (headerOffset < data.size) {
                    val nalType = data[headerOffset].toInt() and 0x1F
                    if (nalType == 7 && spsStart == -1) {
                        spsStart = i
                    } else if (nalType == 5 && spsStart != -1) {
                        ppsEnd = i
                        break
                    }
                }
            }
            i++
        }
        if (spsStart != -1 && ppsEnd > spsStart) {
            return data.copyOfRange(spsStart, ppsEnd)
        }
        return null
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MobiDesk:ScreenCaptureServiceWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock acquired for continuous streaming.")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "WakeLock released.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        }
        wakeLock = null
    }

    @Synchronized
    private fun handleStop() {
        if (isStopping) return
        isStopping = true

        Log.i(TAG, "Stopping ScreenCaptureService...")
        isStreaming = false
        isServiceRunning = false

        unregisterScreenStateReceiver()

        try {
            drainThread?.join(1000)
        } catch (_: InterruptedException) {}
        drainThread = null

        try {
            presentation?.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing presentation: ${e.message}")
        }
        presentation = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing virtualDisplay: ${e.message}")
        }
        virtualDisplay = null

        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping mediaCodec: ${e.message}")
        }
        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing mediaCodec: ${e.message}")
        }
        mediaCodec = null

        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing inputSurface: ${e.message}")
        }
        inputSurface = null

        try {
            aoaAccessoryManager?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping aoaAccessoryManager: ${e.message}")
        }
        aoaAccessoryManager = null
        cachedCodecConfig = null

        projectionCallback?.let {
            try {
                mediaProjection?.unregisterCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering MediaProjection callback: ${e.message}")
            }
        }
        projectionCallback = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping mediaProjection: ${e.message}")
        }
        mediaProjection = null

        releaseWakeLock()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
        isStopping = false
        Log.i(TAG, "ScreenCaptureService stopped successfully.")
    }

    private fun startForegroundWithNotification() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        val title = "MobiDesk Screen Streaming"
        val message = "Streaming screen over USB Android Open Accessory (AOA 2.0)"
        val iconRes = if (applicationInfo.icon != 0) applicationInfo.icon else android.R.drawable.ic_menu_camera

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(iconRes)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(iconRes)
                .setOngoing(true)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Screen Sharing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification displayed while MobiDesk is streaming device screen"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handleStop()
        super.onDestroy()
    }
}
