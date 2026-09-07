package com.mobidesk.mobidesk_app

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import kotlin.concurrent.thread

/**
 * Native full-screen Activity that hosts a SurfaceView and a MediaCodec hardware decoder
 * for MIME type video/avc (H.264). Connects via StreamClient to receive the raw H.264
 * stream over TCP from the host device (USB tethering gateway) and renders the frames to the screen.
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

    private lateinit var surfaceView: SurfaceView
    private var mediaCodec: MediaCodec? = null
    private var streamClient: StreamClient? = null
    private var drainThread: Thread? = null

    @Volatile
    private var isDecoding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake while receiver is displaying video
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Enter native immersive full-screen mode
        applyFullScreen()

        // Create SurfaceView for video rendering
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "Surface created, starting video decoder and client...")
                startDecoderAndClient(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "Surface dimension changed: ${width}x$height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "Surface destroyed, releasing decoder and client...")
                stopDecoderAndClient()
            }
        })
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

    @Synchronized
    private fun startDecoderAndClient(surface: Surface) {
        if (isDecoding) return
        if (!surface.isValid) {
            Log.w(TAG, "Cannot start decoder: Surface is not valid.")
            return
        }
        isDecoding = true

        val host = intent.getStringExtra(EXTRA_HOST) ?: StreamClient.DEFAULT_HOST
        val port = intent.getIntExtra(EXTRA_PORT, StreamClient.DEFAULT_PORT)

        try {
            // Configure decoder for the incoming H.264 stream resolution (720x1280 default)
            // SurfaceView will automatically scale to device screen without level limit crashes on high-res displays
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                DEFAULT_DECODER_WIDTH,
                DEFAULT_DECODER_HEIGHT
            ).apply {
                // Realtime low-latency priority
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                // Ensure input buffers are large enough for high-bitrate keyframes
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
                // Low-latency decoding mode on Android 11+ (API 30+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            // Initialize MediaCodec decoder for MIME type video/avc
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // Pass Surface to MediaCodec decoder and start it
            codec.configure(format, surface, null, 0)
            codec.start()
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            mediaCodec = codec

            Log.i(TAG, "MediaCodec video/avc decoder configured and started.")

            // Drain thread to render decoded frames onto the Surface
            drainThread = thread(name = "ReceiverDrainThread") {
                drainDecoder()
            }

            // Connect StreamClient to feed incoming socket bytes directly into decoder input buffers
            val client = StreamClient(host, port).apply {
                onNalUnitReceived = { data, offset, length ->
                    feedDecoder(data, offset, length)
                }
                onConnected = {
                    Log.i(TAG, "StreamClient connected to host at $host:$port")
                }
                onDisconnected = {
                    Log.i(TAG, "StreamClient disconnected from host")
                }
                onError = { e ->
                    Log.w(TAG, "StreamClient encountered error: ${e.message}")
                }
            }
            client.start()
            streamClient = client

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaCodec decoder or StreamClient: ${e.message}", e)
            stopDecoderAndClient()
        }
    }

    /**
     * Feeds incoming raw H.264 NAL units directly into the MediaCodec decoder's input buffers.
     */
    private fun feedDecoder(data: ByteArray, offset: Int, length: Int) {
        val codec = mediaCodec ?: return
        var currentOffset = offset
        var remaining = length
        val isConfig = isCodecConfig(data, offset, length)

        while (isDecoding && remaining > 0) {
            try {
                val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: break
                    inputBuffer.clear()
                    val toCopy = minOf(remaining, inputBuffer.remaining())
                    inputBuffer.put(data, currentOffset, toCopy)

                    currentOffset += toCopy
                    remaining -= toCopy

                    var flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                    if (remaining > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                    }

                    // Passing presentationTimeUs = 0L renders frame immediately without clock delay
                    codec.queueInputBuffer(inputBufferIndex, 0, toCopy, 0L, flags)
                }
            } catch (e: Exception) {
                if (isDecoding) {
                    Log.e(TAG, "Error queuing input buffer to MediaCodec: ${e.message}")
                }
                break
            }
        }
    }

    /**
     * Checks if a NAL unit is SPS (7) or PPS (8) codec configuration data.
     */
    private fun isCodecConfig(data: ByteArray, offset: Int, length: Int): Boolean {
        var nalHeaderPos = -1
        if (length >= 4 && data[offset] == 0.toByte() && data[offset + 1] == 0.toByte()) {
            if (data[offset + 2] == 1.toByte()) {
                nalHeaderPos = offset + 3
            } else if (data[offset + 2] == 0.toByte() && length >= 5 && data[offset + 3] == 1.toByte()) {
                nalHeaderPos = offset + 4
            }
        }
        if (nalHeaderPos in 0 until (offset + length)) {
            val nalType = data[nalHeaderPos].toInt() and 0x1F
            return nalType == 7 || nalType == 8
        }
        return false
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
                    // Passing true instructs MediaCodec to render the frame directly to the Surface
                    codec.releaseOutputBuffer(outputBufferIndex, true)
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "Decoder output format changed: $newFormat")
                }
            } catch (e: Exception) {
                if (isDecoding) {
                    Log.e(TAG, "Error draining MediaCodec output: ${e.message}")
                }
                break
            }
        }
    }

    @Synchronized
    private fun stopDecoderAndClient() {
        if (!isDecoding) return
        isDecoding = false

        try {
            streamClient?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping StreamClient: ${e.message}")
        }
        streamClient = null

        try {
            drainThread?.join(1000)
        } catch (_: InterruptedException) {}
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

        Log.i(TAG, "Decoder and StreamClient stopped.")
    }

    override fun onDestroy() {
        stopDecoderAndClient()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
