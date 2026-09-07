package com.mobidesk.mobidesk_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Android Foreground Service managing the MediaProjection session,
 * VirtualDisplay, hardware H.264 (video/avc) MediaCodec video encoder,
 * and TCP socket streaming via StreamServer on port 8888.
 *
 * Fully conforms with Android 14 (API 34) requirements:
 * 1. Prompts for user consent per session in MainActivity.
 * 2. Invokes startForeground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION before acquiring MediaProjection.
 * 3. Registers mandatory MediaProjection.Callback prior to creating VirtualDisplay.
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
        const val EXTRA_PORT = "extra_port"

        private const val NOTIFICATION_CHANNEL_ID = "mobidesk_screen_capture"
        private const val NOTIFICATION_ID = 1001

        const val DEFAULT_PORT = 8888
        const val DEFAULT_WIDTH = 720
        const val DEFAULT_HEIGHT = 1280
        const val DEFAULT_FRAME_RATE = 30
        const val DEFAULT_BIT_RATE = 4_000_000 // 4 Mbps
        const val DEFAULT_I_FRAME_INTERVAL = 1 // 1 second
        private const val TIMEOUT_USEC = 10_000L

        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var streamServer: StreamServer? = null

    @Volatile
    private var isStreaming = false
    @Volatile
    private var isStopping = false
    private var drainThread: Thread? = null
    private var serverPort = DEFAULT_PORT

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
            Log.w(TAG, "Screen capture service already active.")
            return
        }

        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
        serverPort = port

        // 1. Android 14 requirement: Start foreground service with MEDIA_PROJECTION type BEFORE getMediaProjection
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

        // 2. Obtain MediaProjection token
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, dataIntent)
        } catch (e: Exception) {
            Log.e(TAG, "SecurityException or error obtaining MediaProjection: ${e.message}", e)
            null
        }

        if (projection == null) {
            Log.e(TAG, "Failed to obtain MediaProjection.")
            handleStop()
            return
        }
        mediaProjection = projection

        // 3. Android 14 requirement: Register MediaProjection callback before createVirtualDisplay
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.i(TAG, "MediaProjection stopped by system callback.")
                handleStop()
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))

        // 4. Determine display dimensions preserving aspect ratio (720p resolution, 30 FPS)
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

        // Ensure even dimensions required by H.264 / AVC video codecs
        if (width % 2 != 0) width--
        if (height % 2 != 0) height--

        // 5. Start TCP Socket Streaming Server on port 8888
        val server = StreamServer(port)
        // Request keyframe as soon as a new client connects so it can start decoding instantly
        server.onClientConnected = {
            requestSyncFrame()
        }
        server.start()
        streamServer = server

        // 6. Initialize MediaCodec video encoder for MIME type video/avc (H.264) at 30 FPS
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, DEFAULT_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL)
                // Use standard VBR for broad hardware compatibility across all Android chipsets
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
                // Realtime low-latency priority
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                // Repeat previous frame on static screen so encoder outputs continuous frames
                setLong(
                    MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                    1_000_000L / DEFAULT_FRAME_RATE
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

            // 7. Create VirtualDisplay directing screen frames to MediaCodec input surface
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

            isStreaming = true
            isServiceRunning = true
            Log.i(TAG, "MediaCodec encoder initialized (${width}x${height} @ ${DEFAULT_FRAME_RATE} FPS, port $port)")

            // 8. In a background thread, read the encoded H.264 byte buffers (NAL units) from MediaCodec
            drainThread = thread(name = "ScreenCaptureDrainThread") {
                drainCodec()
            }

            Log.i(TAG, "Screen capture and streaming successfully started.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaCodec or VirtualDisplay: ${e.message}", e)
            handleStop()
        }
    }

    /**
     * Request an on-demand IDR Sync Frame (Key Frame) from MediaCodec.
     */
    fun requestSyncFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
            Log.i(TAG, "Requested sync frame (keyframe) from MediaCodec")
        } catch (e: Exception) {
            Log.w(TAG, "Could not request sync frame: ${e.message}")
        }
    }

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
                        if (isConfig) {
                            streamServer?.sendConfig(packet)
                        } else {
                            streamServer?.sendPacket(packet)
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
                        val spsPps = ByteArray(csd0.remaining() + csd1.remaining())
                        val spsLen = csd0.remaining()
                        csd0.get(spsPps, 0, spsLen)
                        csd1.get(spsPps, spsLen, csd1.remaining())
                        streamServer?.sendConfig(spsPps)
                    }
                }
            } catch (e: Exception) {
                if (isStreaming) {
                    Log.e(TAG, "Error while draining MediaCodec: ${e.message}")
                }
                break
            }
        }

        // If drainage loop terminated unexpectedly, ensure the service shuts down cleanly
        if (isStreaming) {
            Log.e(TAG, "Drainage thread terminated unexpectedly, stopping service.")
            handleStop()
        }
    }

    @Synchronized
    private fun handleStop() {
        if (isStopping) return
        isStopping = true

        Log.i(TAG, "Stopping screen capture and streaming service...")
        isStreaming = false
        isServiceRunning = false

        try {
            drainThread?.join(1000)
        } catch (_: InterruptedException) {}
        drainThread = null

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
            streamServer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping streamServer: ${e.message}")
        }
        streamServer = null

        // Unregister callback before calling stop to prevent recursive callback invocation
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
        isStopping = false
        Log.i(TAG, "Screen capture service stopped.")
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
        val message = "Sharing screen over local network on port $serverPort"
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
