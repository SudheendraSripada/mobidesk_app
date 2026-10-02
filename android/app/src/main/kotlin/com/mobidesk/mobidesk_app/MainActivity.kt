package com.mobidesk.mobidesk_app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.mobidesk/stream"
    private val REQUEST_CODE_SCREEN_CAPTURE = 1002
    private var pendingResult: MethodChannel.Result? = null

    // Track requested parameters for stream initialization
    private var requestedStreamMode = ScreenCaptureService.STREAM_MODE_MIRROR
    private var requestedGuacUrl: String? = null
    private var requestedWidth = 1920
    private var requestedHeight = 1080
    private var requestedFps = 60

    companion object {
        var lastDockWidth = 1920
        var lastDockHeight = 1080
        var lastDockFps = 60
        var hasReceivedDockDisplayInfo = false
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Initialize and start shared AoaAccessoryManager to detect dock attachment and read monitor info
        try {
            val aoaManager = AoaAccessoryManager.getInstance(this)
            aoaManager.onDisplayInfoReceived = { w, h, fps ->
                lastDockWidth = w
                lastDockHeight = h
                lastDockFps = fps
                hasReceivedDockDisplayInfo = true
            }
            aoaManager.start()
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to start AoaAccessoryManager: ${e.message}")
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startStream", "start" -> {
                    requestedStreamMode = ScreenCaptureService.STREAM_MODE_MIRROR
                    requestedGuacUrl = null
                    promptScreenCapture(result)
                }
                "startMonitorStream" -> {
                    requestedStreamMode = ScreenCaptureService.STREAM_MODE_VIRTUAL_DISPLAY
                    requestedGuacUrl = call.argument<String>("guacUrl")
                    val customWidth = call.argument<Int>("width") ?: lastDockWidth
                    val customHeight = call.argument<Int>("height") ?: lastDockHeight
                    val customFps = call.argument<Int>("fps") ?: lastDockFps
                    requestedWidth = customWidth
                    requestedHeight = customHeight
                    requestedFps = customFps
                    promptScreenCapture(result)
                }
                "startPhoneCloudPc" -> {
                    val sessionUrl = call.argument<String>("sessionUrl")
                    val intent = Intent(this, PhoneCloudPcActivity::class.java).apply {
                        if (!sessionUrl.isNullOrEmpty()) {
                            putExtra(PhoneCloudPcActivity.EXTRA_SESSION_URL, sessionUrl)
                        }
                    }
                    startActivity(intent)
                    result.success(true)
                }
                "stopStream", "stop" -> {
                    val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_STOP
                    }
                    try {
                        startService(stopIntent)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("STOP_ERROR", "Failed to stop ScreenCaptureService: ${e.message}", null)
                    }
                }
                "isStreaming", "getStatus" -> {
                    result.success(ScreenCaptureService.isServiceRunning)
                }
                "isFallbackActive" -> {
                    result.success(ScreenCaptureService.isFallbackActive)
                }
                "getDockDisplayInfo" -> {
                    try {
                        val aoa = AoaAccessoryManager.getInstance(this)
                        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
                        val hasAccessory = aoa.isConnected || (usbManager?.accessoryList?.isNotEmpty() == true)
                        val hasInfo = hasReceivedDockDisplayInfo || aoa.hasDisplayInfo
                        val width = if (hasInfo) lastDockWidth.takeIf { it > 0 } ?: aoa.lastDisplayWidth else lastDockWidth
                        val height = if (hasInfo) lastDockHeight.takeIf { it > 0 } ?: aoa.lastDisplayHeight else lastDockHeight
                        val fps = if (hasInfo) lastDockFps.takeIf { it > 0 } ?: aoa.lastDisplayFps else lastDockFps
                        val info = mapOf(
                            "hasDock" to hasAccessory,
                            "hasReceivedInfo" to hasInfo,
                            "width" to width,
                            "height" to height,
                            "fps" to fps,
                            "isStreaming" to ScreenCaptureService.isServiceRunning,
                            "isFallback" to ScreenCaptureService.isFallbackActive
                        )
                        result.success(info)
                    } catch (e: Exception) {
                        result.error("DISPLAY_INFO_ERROR", "Failed to get display info: ${e.message}", null)
                    }
                }
                "startReceiver" -> {
                    val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
                    UsbHostReceiver.handleStartReceiver(this, usbManager, result)
                }
                "getUsbStatus" -> {
                    try {
                        val aoa = AoaAccessoryManager.getInstance(this)
                        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
                        val hasAccessory = aoa.isConnected || (usbManager?.accessoryList?.isNotEmpty() == true)
                        val deviceCount = usbManager?.deviceList?.size ?: 0
                        val status = mapOf(
                            "hasAccessory" to hasAccessory,
                            "deviceCount" to deviceCount,
                            "isStreaming" to ScreenCaptureService.isServiceRunning,
                            "isFallback" to ScreenCaptureService.isFallbackActive
                        )
                        result.success(status)
                    } catch (e: Exception) {
                        result.error("USB_STATUS_ERROR", "Failed to get USB status: ${e.message}", null)
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun promptScreenCapture(result: MethodChannel.Result) {
        if (ScreenCaptureService.isServiceRunning) {
            result.success(true)
            return
        }
        if (pendingResult != null) {
            result.error("BUSY", "Another consent request is pending", null)
            return
        }
        pendingResult = result

        try {
            // Android 14 requirement: Prompt user consent per session
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = projectionManager.createScreenCaptureIntent()
            startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE)
        } catch (e: Exception) {
            pendingResult = null
            result.error("CONSENT_ERROR", "Failed to prompt for screen capture consent: ${e.message}", null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            val result = pendingResult
            pendingResult = null

            if (resultCode == Activity.RESULT_OK && data != null) {
                val metrics = resources.displayMetrics
                val screenW = metrics.widthPixels
                val screenH = metrics.heightPixels
                val isPortrait = screenH >= screenW

                val (width, height, fps) = if (requestedStreamMode == ScreenCaptureService.STREAM_MODE_VIRTUAL_DISPLAY) {
                    Triple(requestedWidth, requestedHeight, requestedFps)
                } else {
                    // Target 720p preserving device screen aspect ratio
                    val targetShort = ScreenCaptureService.DEFAULT_WIDTH
                    val minDim = minOf(screenW, screenH)
                    val maxDim = maxOf(screenW, screenH)
                    val targetLong = if (minDim > 0) {
                        ((targetShort.toDouble() / minDim) * maxDim).toInt().let {
                            if (it % 2 != 0) it - 1 else it
                        }
                    } else {
                        ScreenCaptureService.DEFAULT_HEIGHT
                    }
                    val w = if (isPortrait) targetShort else targetLong
                    val h = if (isPortrait) targetLong else targetShort
                    Triple(w, h, ScreenCaptureService.DEFAULT_FRAME_RATE)
                }

                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA_INTENT, data)
                    putExtra(ScreenCaptureService.EXTRA_WIDTH, width)
                    putExtra(ScreenCaptureService.EXTRA_HEIGHT, height)
                    putExtra(ScreenCaptureService.EXTRA_DPI, metrics.densityDpi)
                    putExtra(ScreenCaptureService.EXTRA_FPS, fps)
                    putExtra(ScreenCaptureService.EXTRA_STREAM_MODE, requestedStreamMode)
                    putExtra(ScreenCaptureService.EXTRA_GUAC_URL, requestedGuacUrl)
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    result?.success(true)
                } catch (e: Exception) {
                    result?.error("SERVICE_START_FAILED", "Failed to start ScreenCaptureService: ${e.message}", null)
                }
            } else {
                result?.error("PERMISSION_DENIED", "Screen capture permission was denied by user", null)
            }
        }
    }
}
