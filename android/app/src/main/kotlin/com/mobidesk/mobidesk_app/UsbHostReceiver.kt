package com.mobidesk.mobidesk_app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Native Android USB Host receiver implementing the safe AOA (Android Open Accessory)
 * transport layer, runtime permissions, isolated background handshake, and endpoint verification.
 */
class UsbHostReceiver(
    private val context: Context,
    usbManager: UsbManager? = null,
    private val onFrameReceived: ((type: Byte, flags: Byte, ptsUs: Long, payload: ByteArray) -> Unit)? = null
) {
    private val usbManager: UsbManager? = usbManager ?: (context.getSystemService(Context.USB_SERVICE) as? UsbManager)
    companion object {
        private const val TAG = "UsbHostReceiver"
        const val ACTION_USB_PERMISSION = "com.mobidesk.mobidesk_app.USB_PERMISSION"

        // Google Vendor ID used for Android Open Accessory
        const val AOA_VENDOR_ID = 0x18D1

        // AOA Product IDs
        val AOA_PRODUCT_IDS = setOf(
            0x2D00, // accessory
            0x2D01, // accessory + adb
            0x2D02, // audio
            0x2D03, // audio + adb
            0x2D04, // accessory + audio
            0x2D05  // accessory + audio + adb
        )

        // AOA Protocol control requests
        const val AOA_GET_PROTOCOL = 51
        const val AOA_SEND_STRING = 52
        const val AOA_START_ACCESSORY = 53

        // AOA Identification Strings
        const val MANUFACTURER = "MobiDesk"
        const val MODEL = "MobiDeskDock"
        const val DESCRIPTION = "MobiDesk Screen Receiver Dock"
        const val VERSION = "1.0"
        const val URI = "https://github.com/mobidesk"
        const val SERIAL = "0000000012345678"

        private const val BULK_TRANSFER_TIMEOUT_MS = 2000
        private const val READ_BUFFER_SIZE = 64 * 1024
        private const val RETRY_DELAY_MS = 2000L

        /**
         * Safely inspects the connected USB devices without throwing or calling .first()
         * on an empty map. Returns the first candidate USB device or null if none connected.
         */
        fun findConnectedDevice(usbManager: UsbManager?): UsbDevice? {
            val devices = usbManager?.deviceList ?: return null
            if (devices.isEmpty()) return null

            // Prioritize an already enumerated AOA accessory device if present
            for (dev in devices.values) {
                if (isAccessoryDevice(dev)) {
                    return dev
                }
            }

            // Otherwise pick first non-hub device that declares interfaces
            for (dev in devices.values) {
                if (dev.deviceClass == UsbConstants.USB_CLASS_HUB) {
                    continue
                }
                return dev
            }

            return devices.values.firstOrNull()
        }

        /**
         * Checks if the VID/PID matches the standard Google AOA Accessory VID and known AOA PIDs.
         */
        fun isAccessory(vendorId: Int, productId: Int): Boolean {
            return vendorId == AOA_VENDOR_ID && productId in AOA_PRODUCT_IDS
        }

        /**
         * Checks if the device matches the standard Google AOA Accessory VID/PID.
         */
        fun isAccessoryDevice(device: UsbDevice): Boolean {
            return isAccessory(device.vendorId, device.productId)
        }

        /**
         * Safe device detection and permission handling for Flutter's startReceiver method channel call.
         * If no device is connected, returns result.error("NO_DEVICE", ...) without throwing.
         */
        fun handleStartReceiver(
            activity: Activity,
            usbManager: UsbManager?,
            result: MethodChannel.Result
        ) {
            try {
                val manager = usbManager ?: activity.getSystemService(Context.USB_SERVICE) as? UsbManager
                if (manager == null) {
                    Log.w(TAG, "USB Manager unavailable; returning NO_DEVICE to Flutter")
                    result.error("NO_DEVICE", "Please connect Phone A using an OTG adapter", null)
                    return
                }

                // 1. Safe Device Detection:
                // Check usbManager.deviceList safely. If it is empty, DO NOT throw an exception and DO NOT call .first().
                // Return a clean result.error("NO_DEVICE", "Please connect Phone A using an OTG adapter", null) to Flutter.
                val devices = manager.deviceList
                if (devices == null || devices.isEmpty()) {
                    Log.i(TAG, "No USB devices connected; returning clean NO_DEVICE error to Flutter")
                    result.error("NO_DEVICE", "Please connect Phone A using an OTG adapter", null)
                    return
                }

                val device = findConnectedDevice(manager)
                if (device == null) {
                    Log.i(TAG, "No compatible USB candidate found; returning NO_DEVICE to Flutter")
                    result.error("NO_DEVICE", "Please connect Phone A using an OTG adapter", null)
                    return
                }

                // If a device is found, verify if usbManager.hasPermission(device) is true.
                // If false, request permission using a PendingIntent for ACTION_USB_PERMISSION and listen via BroadcastReceiver.
                if (!manager.hasPermission(device)) {
                    Log.i(TAG, "USB permission missing for device: ${device.deviceName}. Requesting permission...")
                    requestUsbPermission(activity, manager, device) { granted ->
                        if (granted) {
                            launchReceiverActivity(activity)
                            result.success(true)
                        } else {
                            result.error("PERMISSION_DENIED", "USB permission denied by user", null)
                        }
                    }
                    return
                }

                // Permission already granted: launch ReceiverActivity
                launchReceiverActivity(activity)
                result.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error in handleStartReceiver: ${e.message}", e)
                result.error("RECEIVER_START_FAILED", "Failed to start receiver: ${e.message}", null)
            }
        }

        /**
         * Starts ReceiverActivity safely.
         */
        fun launchReceiverActivity(activity: Activity) {
            val intent = Intent(activity, ReceiverActivity::class.java).apply {
                putExtra(ReceiverActivity.EXTRA_HOST, "")
                putExtra(ReceiverActivity.EXTRA_PORT, 8888)
            }
            activity.startActivity(intent)
        }

        private fun postToMain(action: () -> Unit) {
            val looper = Looper.getMainLooper()
            if (looper != null) {
                Handler(looper).post(action)
            } else {
                action()
            }
        }

        /**
         * Requests explicit USB runtime permission using a PendingIntent and registers
         * a BroadcastReceiver to listen for the user's response.
         * Returns a cancelable handle to unregister the receiver if cancelled or timed out.
         */
        fun requestUsbPermission(
            context: Context,
            usbManager: UsbManager,
            device: UsbDevice,
            callback: (Boolean) -> Unit
        ): () -> Unit {
            if (usbManager.hasPermission(device)) {
                postToMain {
                    callback(true)
                }
                return {}
            }

            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            var isRegistered = false

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(recvContext: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_USB_PERMISSION) {
                        @Suppress("DEPRECATION")
                        val intentDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (intentDevice != null && intentDevice.deviceName != device.deviceName) {
                            return
                        }
                        if (completed.compareAndSet(false, true)) {
                            try {
                                context.unregisterReceiver(this)
                            } catch (_: Exception) {}
                            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            Log.i(TAG, "USB permission callback received for ${device.deviceName}, granted=$granted")
                            postToMain {
                                callback(granted)
                            }
                        }
                    }
                }
            }

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                isRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register USB permission broadcast receiver: ${e.message}")
                if (completed.compareAndSet(false, true)) {
                    postToMain { callback(false) }
                }
                return {}
            }

            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val requestCode = device.deviceId and 0x7FFFFFFF
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(context.packageName)
                },
                pendingFlags
            )

            try {
                usbManager.requestPermission(device, permissionIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call usbManager.requestPermission: ${e.message}", e)
                if (completed.compareAndSet(false, true)) {
                    if (isRegistered) {
                        try {
                            context.unregisterReceiver(receiver)
                        } catch (_: Exception) {}
                    }
                    postToMain { callback(false) }
                }
            }

            return {
                if (completed.compareAndSet(false, true)) {
                    if (isRegistered) {
                        try {
                            context.unregisterReceiver(receiver)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
        onFrameReceived?.invoke(type, flags, ptsUs, payload)
    }

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var isStreaming = false
        private set

    private var workerThread: Thread? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    var onBulkInReady: ((UsbEndpoint) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    /**
     * Starts the isolated background worker thread to handle device connection,
     * AOA handshake, interface claiming, and bulk transfer reads.
     */
    fun start() {
        if (isRunning) return
        isRunning = true

        workerThread = thread(name = "UsbHostReceiverWorker") {
            runWorkerLoop()
        }
        Log.i(TAG, "UsbHostReceiver background worker launched")
    }

    private fun runWorkerLoop() {
        val manager = usbManager ?: context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (manager == null) {
            onError?.invoke("USB Manager service is unavailable")
            return
        }

        while (isRunning) {
            try {
                // 1. Safe Device Detection:
                val deviceList = manager.deviceList
                if (deviceList == null || deviceList.isEmpty()) {
                    onStatusChanged?.invoke("No USB devices detected. Please connect Phone A using an OTG adapter.")
                    Thread.sleep(RETRY_DELAY_MS)
                    continue
                }

                var device = findConnectedDevice(manager)
                if (device == null) {
                    onStatusChanged?.invoke("No compatible USB device found. Waiting for connection...")
                    Thread.sleep(RETRY_DELAY_MS)
                    continue
                }

                // 2. Permission check:
                if (!manager.hasPermission(device)) {
                    Log.i(TAG, "Awaiting USB runtime permission on worker thread for ${device.deviceName}")
                    onStatusChanged?.invoke("Requesting USB device permission...")
                    val permLatch = CountDownLatch(1)
                    var permissionGranted = false
                    val cancelPerm = requestUsbPermission(context, manager, device) { granted ->
                        permissionGranted = granted
                        permLatch.countDown()
                    }
                    try {
                        permLatch.await(45, TimeUnit.SECONDS)
                    } finally {
                        cancelPerm()
                    }
                    if (!permissionGranted) {
                        onError?.invoke("USB permission denied for ${device.deviceName}")
                        Thread.sleep(RETRY_DELAY_MS)
                        continue
                    }
                    device = findConnectedDevice(manager) ?: device
                }

                // 3. Isolated Background Handshake (51 -> 52 -> 53) & Bulk IN Endpoint Verification:
                onStatusChanged?.invoke("Establishing AOA USB connection...")
                val (conn, endpointInVerified, iface, endpointOutFound) = establishAoaConnection(manager, device)
                usbConnection = conn
                inEndpoint = endpointInVerified
                claimedInterface = iface
                outEndpoint = endpointOutFound

                demuxer.reset()
                isStreaming = true
                Log.i(TAG, "Bulk IN endpoint verified (${endpointInVerified.address}), notifying receiver...")
                onStatusChanged?.invoke("Connected! Video stream active.")
                onConnected?.invoke()

                // Trigger callback so SurfaceView and MediaCodec decoder are initialized
                onBulkInReady?.invoke(endpointInVerified)

                // Request initial keyframe from Sender over Bulk OUT if present
                requestKeyframeFromSender()

                // 4. Bulk IN Reading Loop:
                val buffer = ByteArray(READ_BUFFER_SIZE)
                var consecutiveFailures = 0
                while (isRunning && isStreaming && usbConnection != null && inEndpoint != null) {
                    val bytesRead = conn.bulkTransfer(
                        endpointInVerified,
                        buffer,
                        buffer.size,
                        BULK_TRANSFER_TIMEOUT_MS
                    )
                    if (bytesRead > 0) {
                        consecutiveFailures = 0
                        demuxer.feedData(buffer, 0, bytesRead)
                    } else if (bytesRead < 0) {
                        // Negative code indicates timeout or USB stall; check if device physically disconnected
                        if (!isRunning || !isStreaming) break

                        consecutiveFailures++
                        val currentDeviceList = manager.deviceList
                        val stillConnected = currentDeviceList?.values?.any { it.deviceName == device.deviceName } == true
                        if (!stillConnected) {
                            Log.i(TAG, "USB device ${device.deviceName} physically disconnected during stream")
                            break
                        }

                        if (consecutiveFailures >= 3) {
                            try {
                                Thread.sleep(50)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "UsbHostReceiver worker exception: ${e.message}", e)
                    onError?.invoke("USB stream error: ${e.message}")
                }
            } finally {
                cleanupConnection()
            }

            if (isRunning) {
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    if (!isRunning) break
                }
            }
        }

        Log.i(TAG, "UsbHostReceiver background worker finished")
    }

    /**
     * Executes AOA control transfers (51 -> 52 -> 53) strictly inside this background thread,
     * opens the device, claims the interface, and locates the Bulk IN endpoint.
     */
    private fun establishAoaConnection(
        manager: UsbManager,
        initialDevice: UsbDevice
    ): Quadruple<UsbDeviceConnection, UsbEndpoint, UsbInterface, UsbEndpoint?> {
        var currentDevice = initialDevice

        // If the device is not already in AOA accessory mode, perform control transfers 51 -> 52 -> 53
        if (!isAccessoryDevice(currentDevice)) {
            Log.i(TAG, "Device is not in AOA accessory mode. Executing background handshake 51 -> 52 -> 53...")
            onStatusChanged?.invoke("Executing AOA control handshake (51 -> 52 -> 53)...")
            var handshakeConnection: UsbDeviceConnection? = null
            var handshakeSucceeded = false
            try {
                handshakeConnection = manager.openDevice(currentDevice)
                if (handshakeConnection != null) {
                    handshakeSucceeded = performAoaHandshake(handshakeConnection)
                } else {
                    Log.w(TAG, "Unable to open device connection for AOA handshake")
                }
            } catch (e: Exception) {
                Log.w(TAG, "AOA handshake exception: ${e.message}", e)
            } finally {
                try {
                    handshakeConnection?.close()
                } catch (_: Exception) {}
            }

            if (!handshakeSucceeded) {
                throw IllegalStateException("AOA control handshake failed on ${currentDevice.deviceName}")
            }

            // Wait for re-enumeration as accessory device (up to 12s for slow OEM reboots)
            val deadline = System.currentTimeMillis() + 12000L
            var foundAccessory = false
            while (isRunning && System.currentTimeMillis() < deadline) {
                val list = manager.deviceList
                val accessory = list?.values?.firstOrNull { isAccessoryDevice(it) }
                if (accessory != null) {
                    currentDevice = accessory
                    foundAccessory = true
                    Log.i(TAG, "Re-enumerated AOA accessory device found: ${accessory.deviceName}")
                    break
                }
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
            }

            if (!foundAccessory || !isAccessoryDevice(currentDevice)) {
                throw IllegalStateException("Device did not re-enumerate in AOA accessory mode")
            }
        }

        // Ensure permission on accessory device if re-enumerated
        if (!manager.hasPermission(currentDevice)) {
            val permLatch = CountDownLatch(1)
            var granted = false
            val cancelPerm = requestUsbPermission(context, manager, currentDevice) {
                granted = it
                permLatch.countDown()
            }
            try {
                permLatch.await(45, TimeUnit.SECONDS)
            } finally {
                cancelPerm()
            }
            if (!granted) {
                throw SecurityException("USB permission denied for AOA accessory")
            }
        }

        // Open device wrapped in strict try-catch (e: Exception)
        val connection = try {
            manager.openDevice(currentDevice)
        } catch (e: Exception) {
            Log.e(TAG, "Strict catch: Failed to open USB device connection: ${e.message}", e)
            null
        } ?: throw IllegalStateException("Unable to open USB connection to device")

        // Locate interface and verified Bulk IN endpoint
        val (targetIface, targetInEp, targetOutEp) = findEndpoints(currentDevice)
            ?: run {
                try { connection.close() } catch (_: Exception) {}
                throw IllegalStateException("No Bulk IN endpoint found on USB device")
            }

        // Claim interface strictly wrapped in try-catch (e: Exception)
        val claimed = try {
            connection.claimInterface(targetIface, true)
        } catch (e: Exception) {
            Log.e(TAG, "Strict catch: Failed to claim USB interface: ${e.message}", e)
            false
        }

        if (!claimed) {
            try { connection.close() } catch (_: Exception) {}
            throw IllegalStateException("Failed to claim USB interface on accessory device")
        }

        return Quadruple(connection, targetInEp, targetIface, targetOutEp)
    }

    /**
     * Sends AOA Protocol handshake requests 51 (get protocol), 52 (send strings),
     * and 53 (start accessory).
     */
    private fun performAoaHandshake(connection: UsbDeviceConnection): Boolean {
        try {
            // Request 51: Get protocol version
            val protocolBuf = ByteArray(2)
            val protoLen = connection.controlTransfer(
                0xC0, // USB_DIR_IN | USB_TYPE_VENDOR
                AOA_GET_PROTOCOL,
                0,
                0,
                protocolBuf,
                2,
                BULK_TRANSFER_TIMEOUT_MS
            )
            if (protoLen < 2) {
                Log.w(TAG, "AOA get protocol returned $protoLen; device does not support AOA")
                return false
            }

            val version = (protocolBuf[1].toInt() shl 8) or (protocolBuf[0].toInt() and 0xFF)
            Log.i(TAG, "AOA protocol version supported: $version")
            if (version < 1) {
                Log.w(TAG, "Unsupported AOA protocol version: $version")
                return false
            }

            // Request 52: Send identifying strings
            val strings = arrayOf(
                MANUFACTURER, // 0
                MODEL,        // 1
                DESCRIPTION,  // 2
                VERSION,      // 3
                URI,          // 4
                SERIAL        // 5
            )

            for (i in strings.indices) {
                val strBytes = (strings[i] + "\u0000").toByteArray(Charsets.UTF_8)
                val sent = connection.controlTransfer(
                    0x40, // USB_DIR_OUT | USB_TYPE_VENDOR
                    AOA_SEND_STRING,
                    0,
                    i,
                    strBytes,
                    strBytes.size,
                    BULK_TRANSFER_TIMEOUT_MS
                )
                if (sent < 0) {
                    Log.w(TAG, "Failed sending AOA string $i: $sent")
                }
            }

            // Request 53: Tell device to restart in accessory mode
            val startRes = connection.controlTransfer(
                0x40, // USB_DIR_OUT | USB_TYPE_VENDOR
                AOA_START_ACCESSORY,
                0,
                0,
                null,
                0,
                BULK_TRANSFER_TIMEOUT_MS
            )
            Log.i(TAG, "AOA start accessory request 53 completed with result $startRes")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "AOA handshake error: ${e.message}", e)
            return false
        }
    }

    /**
     * Inspects device interfaces to find Bulk IN and optional Bulk OUT endpoints.
     */
    private fun findEndpoints(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? {
        var fallback: Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) {
                        inEp = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) {
                        outEp = ep
                    }
                }
            }
            if (inEp != null && outEp != null) {
                return Triple(iface, inEp, outEp)
            } else if (inEp != null && fallback == null) {
                fallback = Triple(iface, inEp, outEp)
            }
        }
        return fallback
    }

    /**
     * Sends an initial keyframe request over the Bulk OUT endpoint if available.
     */
    fun requestKeyframeFromSender() {
        val conn = usbConnection ?: return
        val outEp = outEndpoint ?: return
        thread(name = "AoaKeyframeReqThread") {
            try {
                val header = FramingProtocol.createHeader(
                    FramingProtocol.TYPE_HEARTBEAT,
                    FramingProtocol.FLAG_KEYFRAME,
                    0,
                    0L
                )
                conn.bulkTransfer(outEp, header, header.size, 1000)
                Log.i(TAG, "Sent keyframe request to sender over Bulk OUT endpoint.")
            } catch (e: Exception) {
                Log.w(TAG, "Could not send keyframe request: ${e.message}")
            }
        }
    }

    private fun cleanupConnection() {
        val wasConnected = isStreaming || usbConnection != null
        isStreaming = false
        try {
            claimedInterface?.let { usbConnection?.releaseInterface(it) }
        } catch (_: Exception) {}
        try {
            usbConnection?.close()
        } catch (_: Exception) {}
        usbConnection = null
        claimedInterface = null
        inEndpoint = null
        outEndpoint = null
        demuxer.reset()
        if (wasConnected) {
            onDisconnected?.invoke()
        }
    }

    /**
     * Cleanly stops the receiver worker thread and releases all USB resources.
     */
    fun stop() {
        isRunning = false
        isStreaming = false
        workerThread?.interrupt()
        cleanupConnection()
        try {
            workerThread?.join(1000)
        } catch (_: InterruptedException) {}
        workerThread = null
        Log.i(TAG, "UsbHostReceiver stopped")
    }

    /**
     * Wakes the background worker thread immediately to scan for newly connected or re-enumerated devices.
     */
    fun triggerScan() {
        workerThread?.interrupt()
    }

    fun isConnected(): Boolean = usbConnection != null && inEndpoint != null
}

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
