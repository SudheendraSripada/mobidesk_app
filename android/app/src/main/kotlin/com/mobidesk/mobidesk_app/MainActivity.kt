package com.mobidesk.mobidesk_app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.mobidesk/stream"
    private val REQUEST_CODE_SCREEN_CAPTURE = 1002
    private var pendingResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startStream", "start" -> {
                    if (ScreenCaptureService.isServiceRunning) {
                        result.success(true)
                        return@setMethodCallHandler
                    }
                    if (pendingResult != null) {
                        result.error("BUSY", "Another consent request is pending", null)
                        return@setMethodCallHandler
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
                "startReceiver" -> {
                    try {
                        val host = call.argument<String>("host") ?: StreamClient.DEFAULT_HOST
                        val port = call.argument<Int>("port") ?: StreamClient.DEFAULT_PORT
                        val intent = Intent(this, ReceiverActivity::class.java).apply {
                            putExtra(ReceiverActivity.EXTRA_HOST, host)
                            putExtra(ReceiverActivity.EXTRA_PORT, port)
                        }
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("RECEIVER_START_FAILED", "Failed to start ReceiverActivity: ${e.message}", null)
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
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

                // Target 720p while preserving device screen aspect ratio
                val targetShort = 720
                val minDim = minOf(screenW, screenH)
                val maxDim = maxOf(screenW, screenH)
                val targetLong = if (minDim > 0) {
                    ((targetShort.toDouble() / minDim) * maxDim).toInt().let {
                        if (it % 2 != 0) it - 1 else it
                    }
                } else {
                    1280
                }

                val width = if (isPortrait) targetShort else targetLong
                val height = if (isPortrait) targetLong else targetShort

                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA_INTENT, data)
                    putExtra(ScreenCaptureService.EXTRA_WIDTH, width)
                    putExtra(ScreenCaptureService.EXTRA_HEIGHT, height)
                    putExtra(ScreenCaptureService.EXTRA_DPI, metrics.densityDpi)
                    putExtra(ScreenCaptureService.EXTRA_PORT, StreamServer.DEFAULT_PORT)
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
