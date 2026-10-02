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
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
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

        @Volatile
        var currentFps: Float = 0f

        @Volatile
        var currentKbps: Int = 0

        @Volatile
        var totalFramesSent: Long = 0L

        @Volatile
        var droppedFramesCount: Long = 0L

        @Volatile
        var keyframesSentCount: Long = 0L

        @Volatile
        var keyframeRequestsCount: Long = 0L

        @Volatile
        var fallbackNotice: String? = null

        @Volatile
        var activeFgsType: Int = 0

        @Volatile
        var activeWidth: Int = DEFAULT_WIDTH

        @Volatile
        var activeHeight: Int = DEFAULT_HEIGHT

        @Volatile
        private var instance: ScreenCaptureService? = null

        fun updatePresentationStatus(errorText: String?, countdown: Int = 0) {
            instance?.let { service ->
                service.mainHandler.post {
                    if (errorText != null) {
                        service.presentation?.showErrorScreen(errorText, countdown)
                    } else {
                        service.presentation?.showConnectingScreen("MobiDesk - connecting your Cloud PC...")
                    }
                }
            }
        }

        fun updatePresentationSessionUrl(url: String) {
            instance?.let { service ->
                service.mainHandler.post {
                    service.presentation?.updateSessionUrl(url)
                }
            }
        }

        fun requestKeyframe() {
            instance?.requestSyncFrame()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var aoaAccessoryManager: AoaAccessoryManager? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var presentation: MobiDeskPresentation? = null
    private var currentStreamMode: String = STREAM_MODE_MIRROR

    private var savedResultCode: Int = 0
    private var savedDataIntent: Intent? = null
    private var currentWidth: Int = DEFAULT_WIDTH
    private var currentHeight: Int = DEFAULT_HEIGHT
    private var currentFps: Int = DEFAULT_FRAME_RATE
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedCodecConfig: ByteArray? = null

    @Volatile
    private var isStreaming = false
    @Volatile
    private var isStopping = false
    private var drainThread: Thread? = null

    fun clampSupportedResolution(targetW: Int, targetH: Int, targetFps: Int): Triple<Int, Int, Int> {
        var width = targetW
        var height = targetH
        val fps = minOf(targetFps, 30) // Cap at 30 fps for prototype

        val maxPixels = 1920 * 1080
        if (width * height > maxPixels) {
            val scale = Math.sqrt(maxPixels.toDouble() / (width * height))
            width = (width * scale).toInt()
            height = (height * scale).toInt()
        }

        try {
            val codecList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
            } else null

            val encoderInfo = codecList?.codecInfos?.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
            }

            if (encoderInfo != null) {
                val caps = encoderInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val videoCaps = caps.videoCapabilities
                if (videoCaps != null) {
                    val wAlign = videoCaps.widthAlignment.coerceAtLeast(2)
                    val hAlign = videoCaps.heightAlignment.coerceAtLeast(2)
                    width = (width / wAlign) * wAlign
                    height = (height / hAlign) * hAlign

                    val supportedWidths = videoCaps.supportedWidths
                    val supportedHeights = videoCaps.supportedHeights
                    width = width.coerceIn(supportedWidths.lower, supportedWidths.upper)
                    height = height.coerceIn(supportedHeights.lower, supportedHeights.upper)

                    if (!videoCaps.isSizeSupported(width, height)) {
                        Log.w(TAG, "Target size ${width}x${height} not supported by encoder; falling back to 1280x720")
                        width = 1280
                        height = 720
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking VideoCapabilities: ${e.message}")
        }

        if (width % 2 != 0) width--
        if (height % 2 != 0) height--
        if (width < 320) width = 320
        if (height < 240) height = 240

        return Triple(width, height, fps)
    }

    fun calculateBitrate(width: Int, height: Int): Int {
        val pixels = width * height
        return when {
            pixels >= 1920 * 1080 -> 8_000_000 // 8 Mbps for 1080p
            pixels >= 1280 * 720 -> 4_000_000  // 4 Mbps for 720p
            else -> 2_000_000                  // 2 Mbps
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLogger.init(applicationContext)
        AppLogger.i(TAG, "ScreenCaptureService created")
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

        val streamMode = intent.getStringExtra(EXTRA_STREAM_MODE) ?: STREAM_MODE_MIRROR
        val isVirtualDisplay = streamMode == STREAM_MODE_VIRTUAL_DISPLAY

        // 1. Acquire WakeLock and WifiLock for persistent low-latency streaming
        acquireLocks()

        // 2. Android 14 requirement: Start foreground service with appropriate FGS type
        // Use connectedDevice / specialUse for VirtualDisplay, mediaProjection for Mirror
        startForegroundWithNotification(isVirtualDisplay)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA_INTENT)
        }
        savedResultCode = resultCode
        savedDataIntent = dataIntent

        // 3. Obtain MediaProjection token only if mirror mode (VirtualDisplay doesn't need MediaProjection)
        if (!isVirtualDisplay) {
            if (resultCode == 0 || dataIntent == null) {
                Log.e(TAG, "Missing resultCode or dataIntent for MediaProjection mirror mode.")
                handleStop()
                return
            }

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

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.i(TAG, "MediaProjection stopped by system callback.")
                    handleStop()
                }
            }
            projectionCallback = callback
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        }

        // 4. Determine display dimensions preserving aspect ratio or monitor EDID
        val displayMetrics = resources.displayMetrics
        var targetW = intent.getIntExtra(EXTRA_WIDTH, 0)
        var targetH = intent.getIntExtra(EXTRA_HEIGHT, 0)
        val dpi = intent.getIntExtra(EXTRA_DPI, displayMetrics.densityDpi)
        val requestedFps = intent.getIntExtra(EXTRA_FPS, DEFAULT_FRAME_RATE)

        if (targetW <= 0 || targetH <= 0) {
            if (isVirtualDisplay) {
                targetW = if (MainActivity.hasReceivedDockDisplayInfo && MainActivity.lastDockWidth > 0) {
                    MainActivity.lastDockWidth
                } else {
                    1920
                }
                targetH = if (MainActivity.hasReceivedDockDisplayInfo && MainActivity.lastDockHeight > 0) {
                    MainActivity.lastDockHeight
                } else {
                    1080
                }
            } else {
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

                targetW = if (isPortrait) targetShort else targetLong
                targetH = if (isPortrait) targetLong else targetShort
            }
        }

        // Negotiate & clamp resolution against device hardware capabilities
        val (width, height, frameRate) = clampSupportedResolution(targetW, targetH, requestedFps)
        currentWidth = width
        currentHeight = height
        currentFps = frameRate

        val guacUrl = intent.getStringExtra(EXTRA_GUAC_URL)
        currentStreamMode = streamMode
        isFallbackActive = false

        // 5. Initialize AoaAccessoryManager for USB Open Accessory bulk transfer
        val accessoryMgr = AoaAccessoryManager.getInstance(this).apply {
            onAccessoryConnected = {
                Log.i(TAG, "USB Accessory connected to Host. Sending negotiated display info & keyframe...")
                sendDisplayInfoReply(currentWidth, currentHeight, currentFps)
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
            onDisplayInfoReceived = { w, h, fps ->
                Log.i(TAG, "Host reported display info in service: ${w}x${h} @ ${fps}fps")
                MainActivity.lastDockWidth = w
                MainActivity.lastDockHeight = h
                MainActivity.lastDockFps = fps
                MainActivity.hasReceivedDockDisplayInfo = true
                val (negW, negH, negFps) = clampSupportedResolution(w, h, fps)
                sendDisplayInfoReply(negW, negH, negFps)
            }
            onInputMouseReceived = { normX, normY, buttonMask, wheelDx, wheelDy ->
                mainHandler.post {
                    presentation?.injectMouseEvent(normX, normY, buttonMask, wheelDx, wheelDy)
                }
            }
            onInputKeyReceived = { keyCode, state, modifierMask ->
                mainHandler.post {
                    presentation?.injectKeyEvent(keyCode, state, modifierMask)
                }
            }
        }
        accessoryMgr.start()
        accessoryMgr.ensureSenderRunning()
        aoaAccessoryManager = accessoryMgr

        // 6. Register broadcast receiver for Screen OFF / Screen ON to notify Phone B of sleep state
        registerScreenStateReceiver()

        // 7. Initialize MediaCodec video encoder with CBR and scaled bitrate
        try {
            val bitRate = calculateBitrate(width, height)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
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

            activeWidth = width
            activeHeight = height
            AppLogger.i(TAG, "MediaCodec AVC encoder configured: ${width}x${height} @ ${frameRate}fps, bitrate=$bitRate bps, CBR mode, I-frame interval=${DEFAULT_I_FRAME_INTERVAL}s")

            val surface = inputSurface
            if (surface == null) {
                AppLogger.e(TAG, "MediaCodec input surface is null.")
                handleStop()
                return
            }

            // 8. Create VirtualDisplay directing display frames to MediaCodec input surface
            if (isVirtualDisplay) {
                try {
                    val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    AppLogger.i(TAG, "Creating VirtualDisplay 'MobiDeskMonitorDisplay' (${width}x${height} @ ${frameRate}fps, dpi=$dpi, flags=PRESENTATION|OWN_CONTENT_ONLY)")
                    val vDisplay = displayManager.createVirtualDisplay(
                        "MobiDeskMonitorDisplay",
                        width,
                        height,
                        dpi,
                        surface,
                        flags
                    )
                    virtualDisplay = vDisplay

                    mainHandler.post {
                        try {
                            val activity = MainActivity.currentActivity
                            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                                AppLogger.w(TAG, "No valid foreground Activity for Presentation, activating fallback mirror")
                                fallbackToMirror(width, height, dpi, surface)
                                return@post
                            }
                            val pres = MobiDeskPresentation(activity, vDisplay.display, guacUrl)
                            pres.show()
                            presentation = pres
                            AppLogger.i(TAG, "MobiDeskPresentation launched on VirtualDisplay at ${width}x${height} (guacUrl=${guacUrl ?: "waiting"})")
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Failed to instantiate Presentation on VirtualDisplay, activating fallback mirror: ${e.message}", e)
                            fallbackToMirror(width, height, dpi, surface)
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to create Presentation VirtualDisplay, activating fallback mirror: ${e.message}", e)
                    fallbackToMirror(width, height, dpi, surface)
                }
            } else {
                val proj = mediaProjection
                if (proj != null) {
                    virtualDisplay = proj.createVirtualDisplay(
                        "MobiDeskCapture",
                        width,
                        height,
                        dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface,
                        null,
                        null
                    )
                } else {
                    Log.e(TAG, "MediaProjection is null in mirror mode.")
                    handleStop()
                    return
                }
            }

            isStreaming = true
            isServiceRunning = true
            Log.i(TAG, "MediaCodec CBR encoder initialized (${width}x${height} @ ${frameRate} FPS, bitRate=${bitRate}, mode=$streamMode)")

            // Force immediate keyframe on startup/connection if accessory is already connected
            if (aoaAccessoryManager?.isConnected == true) {
                aoaAccessoryManager?.sendDisplayInfoReply(width, height, frameRate)
                cachedCodecConfig?.let { config ->
                    aoaAccessoryManager?.sendConfig(config)
                }
                requestSyncFrame()
            }

            // 9. In background thread, drain encoded NAL units and stream with zero-queue architecture
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
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ) {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            presentation?.dismiss()
        } catch (_: Exception) {}
        presentation = null

        val rCode = savedResultCode
        val dIntent = savedDataIntent
        if (rCode == 0 || dIntent == null) {
            Log.w(TAG, "Cannot activate fallback mirror: MediaProjection consent was not provided.")
            return
        }

        elevateToMediaProjectionFgs()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(rCode, dIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining MediaProjection for fallback mirror: ${e.message}", e)
            null
        }

        if (projection == null) {
            Log.e(TAG, "Failed to obtain MediaProjection for fallback mirror.")
            return
        }
        mediaProjection = projection

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.i(TAG, "Fallback MediaProjection stopped by system callback.")
                handleStop()
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))

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
        var hasLoggedFirstFrame = false
        var lastStatsLogTime = System.currentTimeMillis()
        var intervalFrames = 0L
        var intervalBytes = 0L

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

                            totalFramesSent++
                            intervalFrames++
                            intervalBytes += finalPayload.size
                            if (isKeyframe) {
                                keyframesSentCount++
                            }

                            if (!hasLoggedFirstFrame) {
                                hasLoggedFirstFrame = true
                                AppLogger.i(TAG, "First frame sent to dock at timestamp=${System.currentTimeMillis()} (${finalPayload.size} bytes, isKeyframe=$isKeyframe)")
                            }
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

                // Check 5-second periodic stats
                val now = System.currentTimeMillis()
                if (now - lastStatsLogTime >= 5000L) {
                    val elapsedSec = (now - lastStatsLogTime) / 1000.0f
                    ScreenCaptureService.currentFps = if (elapsedSec > 0f) intervalFrames / elapsedSec else 0f
                    ScreenCaptureService.currentKbps = if (elapsedSec > 0f) ((intervalBytes * 8) / (elapsedSec * 1000)).toInt() else 0
                    val dropped = (aoaAccessoryManager?.droppedFramesCount ?: 0L)
                    droppedFramesCount = dropped
                    val keyframes = keyframesSentCount
                    val wakeHeld = wakeLock?.isHeld == true
                    val wifiHeld = wifiLock?.isHeld == true
                    val fgsTypeName = when (activeFgsType) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE -> "connectedDevice"
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION -> "mediaProjection"
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE -> "specialUse"
                        else -> "none/default($activeFgsType)"
                    }
                    AppLogger.i(TAG, "STREAM STATS: fps=%.1f, kbps=%d, totalFrames=%d, dropped=%d, keyframes=%d, wakeLock=%b, wifiLock=%b, fgsType=%s".format(
                        ScreenCaptureService.currentFps, ScreenCaptureService.currentKbps, totalFramesSent, dropped, keyframes, wakeHeld, wifiHeld, fgsTypeName
                    ))
                    intervalFrames = 0L
                    intervalBytes = 0L
                    lastStatsLogTime = now
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

    private fun acquireLocks() {
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
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager.createWifiLock(lockType, "MobiDesk:ScreenCaptureWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "WifiLock acquired for low-latency streaming.")
            }
        }
    }

    private fun releaseLocks() {
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

        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "WifiLock released.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WifiLock: ${e.message}")
        }
        wifiLock = null
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
            aoaAccessoryManager?.sender?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping aoaAccessoryManager sender: ${e.message}")
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

        releaseLocks()

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

    private fun startForegroundWithNotification(isVirtualDisplay: Boolean) {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val targetType = if (isVirtualDisplay) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            try {
                // Verify prerequisites for connectedDevice on Android 14/15
                if (isVirtualDisplay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    AppLogger.i(TAG, "Android 14/15 FGS connectedDevice prerequisite check: CHANGE_WIFI_STATE / CHANGE_NETWORK_STATE declared in manifest, accessory connected=${aoaAccessoryManager?.isConnected}")
                }
                startForeground(NOTIFICATION_ID, notification, targetType)
                activeFgsType = targetType
                AppLogger.i(TAG, "startForeground succeeded with type: $targetType")
            } catch (e: SecurityException) {
                AppLogger.w(TAG, "SecurityException on startForeground with connectedDevice: ${e.message}; retrying with alternative type specialUse")
                var retrySuccess = false
                if (isVirtualDisplay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    try {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                        activeFgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        retrySuccess = true
                        AppLogger.i(TAG, "startForeground succeeded with fallback type: specialUse")
                    } catch (e2: Exception) {
                        AppLogger.e(TAG, "Failed startForeground with specialUse fallback: ${e2.message}", e2)
                    }
                }
                if (!retrySuccess) {
                    fallbackNotice = "Foreground service permission denied for connectedDevice. Falling back to Mirror Mode."
                    AppLogger.w(TAG, fallbackNotice!!)
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                    } catch (e3: Exception) {
                        AppLogger.e(TAG, "startForeground basic fallback failed: ${e3.message}", e3)
                    }
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
            AppLogger.i(TAG, "startForeground succeeded (legacy mode)")
        }
    }

    private fun elevateToMediaProjectionFgs() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                Log.i(TAG, "Elevated foreground service to MEDIA_PROJECTION")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to elevate foreground service to MEDIA_PROJECTION: ${e.message}")
            }
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
        if (instance == this) {
            instance = null
        }
        AppLogger.i(TAG, "ScreenCaptureService destroyed")
        super.onDestroy()
    }
}
