package com.mobidesk.mobidesk_app

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
import android.util.Log
import kotlin.concurrent.thread

/**
 * Manages the USB Host (OTG) role on Phone B (Receiver / Simulated Dock).
 * Scans connected USB devices, performs Android Open Accessory (AOA 2.0)
 * handshake (control transfers 51 -> 52 -> 53), claims the USB Bulk IN
 * endpoint on the accessory device, and demuxes the streaming binary video frames.
 */
class AoaHostManager(
    private val context: Context,
    private val onFrameReceived: (type: Byte, flags: Byte, ptsUs: Long, payload: ByteArray) -> Unit
) {

    companion object {
        private const val TAG = "AoaHostManager"
        private const val ACTION_USB_HOST_PERMISSION = "com.mobidesk.USB_HOST_PERMISSION"

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

        // AOA 2.0 Control Transfer Requests
        private const val AOA_GET_PROTOCOL = 51
        private const val AOA_SEND_STRING = 52
        private const val AOA_START_ACCESSORY = 53

        // AOA Identification Strings
        const val MANUFACTURER = "MobiDesk"
        const val MODEL = "MobiDeskDock"
        const val DESCRIPTION = "MobiDesk Screen Receiver Dock"
        const val VERSION = "1.0"
        const val URI = "https://github.com/mobidesk"
        const val SERIAL = "0000000012345678"

        private const val BULK_TRANSFER_TIMEOUT_MS = 2000
        private const val READ_BUFFER_SIZE = 64 * 1024
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val demuxer = FramingDemuxer(onFrameReceived)

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var isStreaming = false
        private set

    private var activeDevice: UsbDevice? = null
    private var activeConnection: UsbDeviceConnection? = null
    private var activeInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private var readThread: Thread? = null
    private var receiverRegistered = false

    var onStatusChanged: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    Log.i(TAG, "USB device attached: ${device?.deviceName} (VID=${device?.vendorId}, PID=${device?.productId})")
                    device?.let { handleDevice(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    Log.i(TAG, "USB device detached: ${device?.deviceName}")
                    if (device != null && device.deviceName == activeDevice?.deviceName) {
                        disconnectActiveDevice()
                        onStatusChanged?.invoke("USB device disconnected. Waiting for connection...")
                    }
                }
                ACTION_USB_HOST_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (granted && device != null) {
                        Log.i(TAG, "Permission granted for device: ${device.deviceName}")
                        handleDeviceWithPermission(device)
                    } else {
                        Log.w(TAG, "Permission denied for USB device")
                        onError?.invoke("Permission denied for USB device: ${device?.deviceName}")
                    }
                }
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_HOST_PERMISSION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true

        onStatusChanged?.invoke("Scanning for connected USB devices...")
        scanDevices()
    }

    /**
     * Scan current USB device list and process any matching accessory or candidate device.
     */
    fun scanDevices() {
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            Log.i(TAG, "No USB devices found on host.")
            onStatusChanged?.invoke("No USB devices detected. Connect sender with USB OTG cable.")
            return
        }

        // Priority 1: Check if any device is already in AOA accessory mode
        for (device in deviceList.values) {
            if (isAccessoryDevice(device)) {
                Log.i(TAG, "Found device already in AOA mode: ${device.deviceName}")
                handleDevice(device)
                return
            }
        }

        // Priority 2: Check any connected non-hub USB device to initiate AOA handshake
        for (device in deviceList.values) {
            if (device.deviceClass == UsbConstants.USB_CLASS_HUB) {
                Log.d(TAG, "Skipping USB Hub device: ${device.deviceName}")
                continue
            }
            Log.i(TAG, "Found candidate USB device: ${device.deviceName} (VID=0x${Integer.toHexString(device.vendorId)})")
            handleDevice(device)
            return
        }
    }

    private fun isAccessoryDevice(device: UsbDevice): Boolean {
        return device.vendorId == AOA_VENDOR_ID && device.productId in AOA_PRODUCT_IDS
    }

    private fun handleDevice(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            handleDeviceWithPermission(device)
        } else {
            requestDevicePermission(device)
        }
    }

    private fun requestDevicePermission(device: UsbDevice) {
        Log.i(TAG, "Requesting permission for USB device: ${device.deviceName}")
        onStatusChanged?.invoke("Requesting USB device permission...")
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_HOST_PERMISSION).setPackage(context.packageName),
            flag
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun handleDeviceWithPermission(device: UsbDevice) {
        if (isAccessoryDevice(device)) {
            // Already in accessory mode, establish bulk stream
            onStatusChanged?.invoke("Device in accessory mode. Connecting bulk stream...")
            connectAccessoryStream(device)
        } else {
            // Initiate AOA 2.0 Handshake (51 -> 52 -> 53)
            onStatusChanged?.invoke("Initiating Android Open Accessory handshake...")
            thread(name = "AoaHandshakeThread") {
                initiateAoaHandshake(device)
            }
        }
    }

    /**
     * Executes AOA 2.0 control transfer sequence:
     * 1. Request 51: Get protocol version
     * 2. Request 52: Send 6 identifying strings
     * 3. Request 53: Switch device to accessory mode
     */
    private fun initiateAoaHandshake(device: UsbDevice) {
        Log.i(TAG, "Starting AOA handshake on device ${device.deviceName}")
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            val err = "Failed to open connection to ${device.deviceName} for AOA handshake."
            Log.e(TAG, err)
            onError?.invoke(err)
            return
        }

        try {
            // Step 1: Query AOA Protocol Version (Control Transfer 51)
            // bmRequestType: 0xC0 (USB_DIR_IN | USB_TYPE_VENDOR)
            val versionBuffer = ByteArray(2)
            val protocolLen = connection.controlTransfer(
                0xC0,
                AOA_GET_PROTOCOL,
                0,
                0,
                versionBuffer,
                2,
                BULK_TRANSFER_TIMEOUT_MS
            )

            if (protocolLen < 2) {
                val err = "Device ${device.deviceName} does not respond to AOA protocol query."
                Log.w(TAG, err)
                onError?.invoke(err)
                return
            }

            val protocolVersion = (versionBuffer[1].toInt() shl 8) or (versionBuffer[0].toInt() and 0xFF)
            Log.i(TAG, "Device supports AOA protocol version: $protocolVersion")
            if (protocolVersion < 1) {
                val err = "Device does not support Android Open Accessory (version $protocolVersion)."
                Log.w(TAG, err)
                onError?.invoke(err)
                return
            }

            // Step 2: Send identifying strings (Control Transfer 52)
            // bmRequestType: 0x40 (USB_DIR_OUT | USB_TYPE_VENDOR)
            val strings = arrayOf(
                MANUFACTURER,
                MODEL,
                DESCRIPTION,
                VERSION,
                URI,
                SERIAL
            )

            for (index in strings.indices) {
                val strBytes = (strings[index] + "\u0000").toByteArray(Charsets.UTF_8)
                val len = connection.controlTransfer(
                    0x40,
                    AOA_SEND_STRING,
                    0,
                    index,
                    strBytes,
                    strBytes.size,
                    BULK_TRANSFER_TIMEOUT_MS
                )
                if (len < 0) {
                    Log.w(TAG, "Failed to send AOA string index $index (${strings[index]})")
                }
            }

            // Step 3: Tell device to start in accessory mode (Control Transfer 53)
            Log.i(TAG, "Sending AOA_START_ACCESSORY (request 53)")
            val startRes = connection.controlTransfer(
                0x40,
                AOA_START_ACCESSORY,
                0,
                0,
                null,
                0,
                BULK_TRANSFER_TIMEOUT_MS
            )
            Log.i(TAG, "AOA_START_ACCESSORY sent, result: $startRes")

            onStatusChanged?.invoke("AOA handshake completed. Waiting for device re-enumeration...")

        } catch (e: Exception) {
            Log.e(TAG, "Exception during AOA handshake: ${e.message}", e)
            onError?.invoke("AOA handshake failed: ${e.message}")
        } finally {
            try {
                connection.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Claims Bulk IN endpoint on re-enumerated AOA device and starts streaming.
     */
    @Synchronized
    private fun connectAccessoryStream(device: UsbDevice) {
        if (isStreaming) {
            disconnectActiveDevice()
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            val err = "Failed to open connection to accessory ${device.deviceName}"
            Log.e(TAG, err)
            onError?.invoke(err)
            return
        }

        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        var targetInterface: UsbInterface? = null

        // Locate interface with Bulk IN and Bulk OUT endpoints
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var foundIn: UsbEndpoint? = null
            var foundOut: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN && foundIn == null) {
                        foundIn = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT && foundOut == null) {
                        foundOut = ep
                    }
                }
            }
            if (foundIn != null) {
                inEp = foundIn
                outEp = foundOut
                targetInterface = iface
                break
            }
        }

        if (inEp == null || targetInterface == null) {
            val err = "Failed to find USB Bulk IN endpoint on accessory."
            Log.e(TAG, err)
            onError?.invoke(err)
            connection.close()
            return
        }

        val claimed = connection.claimInterface(targetInterface, true)
        if (!claimed) {
            val err = "Failed to claim interface on accessory."
            Log.e(TAG, err)
            onError?.invoke(err)
            connection.close()
            return
        }

        activeDevice = device
        activeConnection = connection
        activeInterface = targetInterface
        endpointIn = inEp
        endpointOut = outEp

        demuxer.reset()
        isStreaming = true
        Log.i(TAG, "Claimed USB Bulk IN endpoint: ${inEp.address}, starting read loop.")
        onStatusChanged?.invoke("Connected to accessory! Streaming screen...")
        onConnected?.invoke()

        // Request initial keyframe from Phone A over Bulk OUT if available
        requestKeyframeFromSender()

        readThread = thread(name = "AoaBulkReadThread") {
            runBulkReadLoop(connection, inEp)
        }
    }

    /**
     * Sends a keyframe request over Bulk OUT endpoint to the Sender.
     */
    fun requestKeyframeFromSender() {
        val conn = activeConnection ?: return
        val outEp = endpointOut ?: return
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
                Log.w(TAG, "Could not send keyframe request over Bulk OUT: ${e.message}")
            }
        }
    }

    private fun runBulkReadLoop(connection: UsbDeviceConnection, inEndpoint: UsbEndpoint) {
        val readBuffer = ByteArray(READ_BUFFER_SIZE)
        while (isRunning && isStreaming) {
            val bytesRead = connection.bulkTransfer(
                inEndpoint,
                readBuffer,
                readBuffer.size,
                BULK_TRANSFER_TIMEOUT_MS
            )

            if (bytesRead > 0) {
                demuxer.feedData(readBuffer, 0, bytesRead)
            } else if (bytesRead < 0) {
                // Timeout (-1) is normal when no new frames are transmitted.
                // However, check if device was disconnected.
                if (!isRunning || !isStreaming) break
            }
        }
        Log.i(TAG, "Bulk read loop terminated.")
    }

    @Synchronized
    fun disconnectActiveDevice() {
        if (!isStreaming && activeConnection == null) return
        isStreaming = false

        readThread?.interrupt()
        try {
            readThread?.join(1000)
        } catch (_: InterruptedException) {}
        readThread = null

        try {
            activeInterface?.let { activeConnection?.releaseInterface(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing interface: ${e.message}")
        }
        activeInterface = null

        try {
            activeConnection?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing connection: ${e.message}")
        }
        activeConnection = null
        activeDevice = null
        endpointIn = null
        endpointOut = null

        demuxer.reset()
        Log.i(TAG, "AOA host connection disconnected.")
        onDisconnected?.invoke()
    }

    fun stop() {
        isRunning = false
        disconnectActiveDevice()

        if (receiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver: ${e.message}")
            }
            receiverRegistered = false
        }
    }
}

