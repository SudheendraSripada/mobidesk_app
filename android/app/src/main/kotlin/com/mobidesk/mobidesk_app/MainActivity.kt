package com.mobidesk.mobidesk_app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Surface
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

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
    private var autoLaunchMonitor = false

    companion object {
        var currentActivity: Activity? = null
        var lastDockWidth = 1920
        var lastDockHeight = 1080
        var lastDockFps = 60
        var hasReceivedDockDisplayInfo = false
        var pendingDockAttach = false
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        currentActivity = this
        AppLogger.init(applicationContext)
        handleUsbIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1003)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            autoLaunchMonitor = true
            pendingDockAttach = true
            AppLogger.i("MainActivity", "USB accessory attached intent received: set pendingDockAttach=true")
        }
    }

    override fun onResume() {
        super.onResume()
        currentActivity = this
    }

    override fun onDestroy() {
        if (currentActivity == this) {
            currentActivity = null
        }
        super.onDestroy()
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
            aoaManager.onKeyframeRequested = {
                AppLogger.i("MainActivity", "Keyframe requested by dock accessory")
                ScreenCaptureService.requestKeyframe()
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
                    startVirtualDisplayStream(result)
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
                "requestIgnoreBatteryOptimizations" -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
                            if (!isIgnoring) {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                startActivity(intent)
                            }
                            result.success(true)
                        } else {
                            result.success(true)
                        }
                    } catch (e: Exception) {
                        result.error("BATTERY_OPT_ERROR", "Failed to request battery optimization ignore: ${e.message}", null)
                    }
                }
                "isIgnoringBatteryOptimizations" -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                            result.success(powerManager.isIgnoringBatteryOptimizations(packageName))
                        } else {
                            result.success(true)
                        }
                    } catch (e: Exception) {
                        result.success(false)
                    }
                }
                "checkAutoLaunchMonitor" -> {
                    val shouldLaunch = autoLaunchMonitor
                    autoLaunchMonitor = false
                    result.success(shouldLaunch)
                }
                "getStreamStats" -> {
                    val stats = mapOf(
                        "fps" to ScreenCaptureService.currentFps.toDouble(),
                        "kbps" to ScreenCaptureService.currentKbps,
                        "totalFrames" to ScreenCaptureService.totalFramesSent,
                        "droppedFrames" to ScreenCaptureService.droppedFramesCount,
                        "keyframes" to ScreenCaptureService.keyframesSentCount,
                        "keyframeRequests" to ScreenCaptureService.keyframeRequestsCount,
                        "width" to ScreenCaptureService.activeWidth,
                        "height" to ScreenCaptureService.activeHeight,
                        "isStreaming" to ScreenCaptureService.isServiceRunning,
                        "isFallback" to ScreenCaptureService.isFallbackActive,
                        "fallbackNotice" to (ScreenCaptureService.fallbackNotice ?: ""),
                        "fgsType" to ScreenCaptureService.activeFgsType
                    )
                    result.success(stats)
                }
                "updatePresentationStatus" -> {
                    val errorText = call.argument<String>("errorText")
                    val countdown = call.argument<Int>("countdown") ?: 0
                    ScreenCaptureService.updatePresentationStatus(errorText, countdown)
                    result.success(true)
                }
                "updatePresentationSessionUrl" -> {
                    val url = call.argument<String>("url") ?: ""
                    ScreenCaptureService.updatePresentationSessionUrl(url)
                    result.success(true)
                }
                "requestKeyframe" -> {
                    ScreenCaptureService.requestKeyframe()
                    result.success(true)
                }
                "getLogs" -> {
                    val logs = AppLogger.getLogs()
                    result.success(logs)
                }
                "clearLogs" -> {
                    AppLogger.clearLogs()
                    result.success(true)
                }
                "shareLogs" -> {
                    AppLogger.shareLogs(this)
                    result.success(true)
                }
                "logMessage" -> {
                    val level = call.argument<String>("level") ?: "I"
                    val tag = call.argument<String>("tag") ?: "Flutter"
                    val msg = call.argument<String>("message") ?: ""
                    when (level.uppercase()) {
                        "E" -> AppLogger.e(tag, msg)
                        "W" -> AppLogger.w(tag, msg)
                        else -> AppLogger.i(tag, msg)
                    }
                    result.success(true)
                }
                "hasPendingDockAttach" -> {
                    val consume = call.argument<Boolean>("consume") ?: false
                    val pending = pendingDockAttach
                    if (consume) {
                        pendingDockAttach = false
                    }
                    result.success(pending)
                }
                "clearPendingDockAttach" -> {
                    pendingDockAttach = false
                    result.success(true)
                }
                "runSystemCheck" -> {
                    val check = call.argument<String>("check") ?: ""
                    result.success(runSingleSystemCheck(check))
                }
                "runAllSystemChecks" -> {
                    result.success(runAllSystemChecksInternal())
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun startVirtualDisplayStream(result: MethodChannel.Result) {
        if (ScreenCaptureService.isServiceRunning) {
            result.success(true)
            return
        }
        val metrics = resources.displayMetrics
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_WIDTH, requestedWidth)
            putExtra(ScreenCaptureService.EXTRA_HEIGHT, requestedHeight)
            putExtra(ScreenCaptureService.EXTRA_DPI, metrics.densityDpi)
            putExtra(ScreenCaptureService.EXTRA_FPS, requestedFps)
            putExtra(ScreenCaptureService.EXTRA_STREAM_MODE, ScreenCaptureService.STREAM_MODE_VIRTUAL_DISPLAY)
            putExtra(ScreenCaptureService.EXTRA_GUAC_URL, requestedGuacUrl)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            result.success(true)
        } catch (e: Exception) {
            result.error("SERVICE_START_FAILED", "Failed to start ScreenCaptureService: ${e.message}", null)
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

    private fun runSingleSystemCheck(check: String): Map<String, Any> {
        return when (check) {
            "usbAccessory" -> checkUsbAccessory()
            "avcEncoder" -> checkAvcEncoder()
            "virtualDisplay" -> checkVirtualDisplayDryRun()
            "foregroundService" -> checkForegroundService()
            "wifiLock" -> checkWifiLock()
            "wakeLock" -> checkWakeLock()
            "notifications" -> checkNotificationPermission()
            "batteryOptimization" -> checkBatteryOptimization()
            "storage" -> checkStorageWritable()
            "presentationDisplay" -> checkPresentationDisplay()
            "networkConnectivity" -> checkNetworkConnectivity()
            else -> mapOf("pass" to false, "detail" to "Unknown check '$check'")
        }
    }

    private fun runAllSystemChecksInternal(): Map<String, Any> {
        return mapOf(
            "usbAccessory" to checkUsbAccessory(),
            "avcEncoder" to checkAvcEncoder(),
            "virtualDisplay" to checkVirtualDisplayDryRun(),
            "foregroundService" to checkForegroundService(),
            "wifiLock" to checkWifiLock(),
            "wakeLock" to checkWakeLock(),
            "notifications" to checkNotificationPermission(),
            "batteryOptimization" to checkBatteryOptimization(),
            "storage" to checkStorageWritable(),
            "presentationDisplay" to checkPresentationDisplay(),
            "networkConnectivity" to checkNetworkConnectivity()
        )
    }

    private fun checkUsbAccessory(): Map<String, Any> {
        return try {
            val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
            val accessories = usbManager?.accessoryList ?: emptyArray()
            val aoa = AoaAccessoryManager.getInstance(this)
            val isConnected = aoa.isConnected || accessories.isNotEmpty()
            val detail = if (accessories.isNotEmpty()) {
                accessories.joinToString { "${it.manufacturer} ${it.model} (v${it.version})" }
            } else if (isConnected) {
                "AOA Accessory connected"
            } else {
                "No USB accessory attached (Ready for dock)"
            }
            mapOf("pass" to isConnected, "detail" to detail)
        } catch (e: Exception) {
            mapOf("pass" to false, "detail" to "USB check failed: ${e.message}")
        }
    }

    private fun checkAvcEncoder(): Map<String, Any> {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val avcEncoder = codecList.codecInfos.firstOrNull {
                it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
            }
            if (avcEncoder != null) {
                val caps = avcEncoder.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val vCaps = caps.videoCapabilities
                val supported720 = vCaps?.isSizeSupported(1280, 720) ?: false
                val maxW = vCaps?.supportedWidths?.upper ?: 0
                val maxH = vCaps?.supportedHeights?.upper ?: 0
                mapOf("pass" to true, "detail" to "${avcEncoder.name} (720p: $supported720, max: ${maxW}x${maxH})")
            } else {
                mapOf("pass" to false, "detail" to "No H.264/AVC hardware encoder found on device")
            }
        } catch (e: Exception) {
            mapOf("pass" to false, "detail" to "AVC check error: ${e.message}")
        }
    }

    private fun checkVirtualDisplayDryRun(): Map<String, Any> {
        return try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val surfaceTexture = SurfaceTexture(10)
            surfaceTexture.setDefaultBufferSize(1280, 720)
            val surface = Surface(surfaceTexture)
            val vd = dm.createVirtualDisplay(
                "SystemCheckVirtualDisplay",
                1280,
                720,
                160,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            Thread.sleep(50)
            vd?.release()
            surface.release()
            surfaceTexture.release()
            mapOf("pass" to true, "detail" to "1280x720 Presentation VirtualDisplay dry run passed")
        } catch (e: Exception) {
            mapOf("pass" to false, "detail" to "VirtualDisplay dry run failed: ${e.message}")
        }
    }

    private fun checkForegroundService(): Map<String, Any> {
        return try {
            val pm = packageManager
            val hasFgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.checkPermission(android.Manifest.permission.FOREGROUND_SERVICE, packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
            val hasConnectedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pm.checkPermission("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE", packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
            val hasNetworkState = pm.checkPermission(android.Manifest.permission.CHANGE_NETWORK_STATE, packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val pass = hasFgs && hasConnectedDevice
            mapOf("pass" to pass, "detail" to "FGS: $hasFgs, CONNECTED_DEVICE: $hasConnectedDevice, CHANGE_NETWORK_STATE: $hasNetworkState")
        } catch (e: Exception) {
            mapOf("pass" to false, "detail" to "FGS check error: ${e.message}")
        }
    }

    private fun checkWifiLock(): Map<String, Any> {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MobiDesk:CheckWifiLock")
            } else {
                @Suppress("DEPRECATION")
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MobiDesk:CheckWifiLock")
            }
            lock.setReferenceCounted(false)
            lock.acquire()
            val held = lock.isHeld
            lock.release()
            mapOf("pass" to held, "detail" to "Low-latency WifiLock acquired and released cleanly")
        } catch (e: Exception) {
            mapOf("pass" to false, "detail" to "WifiLock error: ${e.message}")
        }
    }

    private fun checkWakeLock(): Map<String, Any> {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MobiDesk:CheckWakeLock")
            lock.setReferenceCounted(false)
            lock.acquire(500)
            val held = lock.isHeld
            lock.release()
            mapOf("pass" to held, "detail" to "Partial WakeLock acquired and released cleanly")
        } catch (e: Exception) {
            mapOf("pass" to false, "detail" to "WakeLock error: ${e.message}")
        }
    }

    private fun checkNotificationPermission(): Map<String, Any> {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        return mapOf(
            "pass" to granted,
            "detail" to if (granted) "POST_NOTIFICATIONS granted" else "POST_NOTIFICATIONS not granted"
        )
    }

    private fun checkBatteryOptimization(): Map<String, Any> {
        return try {
            val isIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
            } else true
            mapOf(
                "pass" to isIgnored,
                "detail" to if (isIgnored) "Unrestricted background execution active" else "Battery optimization active (may throttle USB)"
            )
        } catch (e: Exception) {
            mapOf("pass" to false, "detail" to "Battery check error: ${e.message}")
        }
    }

    private fun checkStorageWritable(): Map<String, Any> {
        return try {
            val dir = filesDir
            val testFile = File(dir, ".system_check_write_test")
            testFile.writeText("mobidesk_check_${System.currentTimeMillis()}")
            val readBack = testFile.readText()
            testFile.delete()
            val freeMb = dir.freeSpace / (1024 * 1024)
            val pass = readBack.startsWith("mobidesk_check_")
            mapOf("pass" to pass, "detail" to "App filesDir writable ($freeMb MB free)")
        } catch (e: Exception) {
            mapOf("pass" to false, "detail" to "Storage write error: ${e.message}")
        }
    }

    private fun checkPresentationDisplay(): Map<String, Any> {
        return try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            mapOf(
                "pass" to true,
                "detail" to "DisplayManager active; ${displays.size} presentation display(s) attached"
            )
        } catch (e: Exception) {
            mapOf("pass" to false, "detail" to "Display check error: ${e.message}")
        }
    }

    private fun checkNetworkConnectivity(): Map<String, Any> {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNet = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNet)
            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val hasValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            mapOf(
                "pass" to hasInternet,
                "detail" to if (hasInternet) "Connected (validated: $hasValidated)" else "No active internet connection"
            )
        } catch (e: Exception) {
            mapOf("pass" to false, "detail" to "Network check error: ${e.message}")
        }
    }
}
