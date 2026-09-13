package com.mobidesk.mobidesk_app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
import kotlin.concurrent.thread

/**
 * Native full-screen Activity on Phone B (Receiver / Simulated Dock).
 * Hosts a SurfaceView, initializes MediaCodec hardware decoder for MIME video/avc (H.264),
 * runs AoaHostManager as USB Host to handshake with Phone A and stream USB Bulk IN packets,
 * and renders decoded frames directly to the SurfaceView.
 */
class ReceiverActivity : Activity() {

    companion object {
        private const val TAG = "ReceiverActivity"
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
    private var aoaHostManager: AoaHostManager? = null
    private var drainThread: Thread? = null

    @Volatile
    private var isDecoding = false
    @Volatile
    private var hasReceivedFirstFrame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake while receiver is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Enter native immersive full-screen mode
        applyFullScreen()

        // Create UI layout programmatically: SurfaceView with a stylish status overlay on top
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
            text = "Initializing AOA 2.0 Host..."
        }
        overlayContent.addView(statusTextView)

        statusOverlay.addView(overlayContent)
        rootLayout.addView(statusOverlay)

        setContentView(rootLayout)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "Surface created, initializing decoder and AOA Host...")
                startDecoderAndHost(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "Surface dimension changed: ${width}x$height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "Surface destroyed, releasing decoder and AOA Host...")
                stopDecoderAndHost()
            }
        })
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent received: action=${intent?.action}")
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            aoaHostManager?.scanDevices()
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

    @Synchronized
    private fun startDecoderAndHost(surface: Surface) {
        if (isDecoding) return
        if (!surface.isValid) {
            Log.w(TAG, "Cannot start decoder: Surface is not valid.")
            return
        }
        isDecoding = true
        hasReceivedFirstFrame = false

        try {
            // Configure MediaCodec decoder for H.264 (video/avc)
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
            Log.i(TAG, "MediaCodec video/avc decoder configured and started.")

            // Drain thread rendering decoded frames directly to the SurfaceView
            drainThread = thread(name = "ReceiverDrainThread") {
                drainDecoder()
            }

            // Start AOA Host Manager to handshake and receive USB bulk video packets
            val hostManager = AoaHostManager(this) { type, flags, ptsUs, payload ->
                feedDecoder(type, flags, ptsUs, payload)
            }.apply {
                onStatusChanged = { status ->
                    updateStatus(status, true)
                }
                onError = { errorMsg ->
                    updateStatus(errorMsg, false)
                }
                onConnected = {
                    updateStatus("Connected! Receiving video stream...", false)
                }
                onDisconnected = {
                    hasReceivedFirstFrame = false
                    updateStatus("USB Accessory disconnected. Reconnecting...", true)
                }
            }
            hostManager.start()
            aoaHostManager = hostManager

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start decoder or AOA Host: ${e.message}", e)
            updateStatus("Failed to initialize decoder: ${e.message}", false)
            stopDecoderAndHost()
        }
    }

    /**
     * Feeds incoming demuxed binary frames directly into MediaCodec decoder.
     */
    private fun feedDecoder(type: Byte, flags: Byte, ptsUs: Long, payload: ByteArray) {
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

                // Use ptsUs or 0L for instant rendering
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
                    // Instruct MediaCodec to render frame directly to SurfaceView
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
    private fun stopDecoderAndHost() {
        if (!isDecoding) return
        isDecoding = false

        try {
            aoaHostManager?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AoaHostManager: ${e.message}")
        }
        aoaHostManager = null

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

        Log.i(TAG, "Decoder and AOA Host stopped.")
    }

    override fun onDestroy() {
        stopDecoderAndHost()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
