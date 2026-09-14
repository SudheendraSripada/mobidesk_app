package com.mobidesk.mobidesk_app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Manages the USB Accessory connection on Phone A (Sender).
 * Opens the UsbAccessory via UsbManager, delegates zero-queue frame transmission
 * to UsbAccessorySender, and monitors accessory attach/detach events.
 */
class AoaAccessoryManager(private val context: Context) {

    companion object {
        private const val TAG = "AoaAccessoryManager"
        private const val ACTION_USB_PERMISSION = "com.mobidesk.USB_ACCESSORY_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null
    private var inputStream: FileInputStream? = null

    // Zero-queue USB sender component
    val sender = UsbAccessorySender().apply {
        onDisconnected = {
            closeAccessory()
        }
    }

    @Volatile
    var isConnected = false
        private set

    @Volatile
    private var isRunning = false

    private var readThread: Thread? = null
    private var receiverRegistered = false

    @Volatile
    private var latestConfigHeader: ByteArray? = null

    var onAccessoryConnected: (() -> Unit)? = null
    var onAccessoryDisconnected: (() -> Unit)? = null
    var onKeyframeRequested: (() -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    @Suppress("DEPRECATION")
                    val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                    } else {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                    }
                    Log.i(TAG, "USB Accessory attached: ${accessory?.description}")
                    accessory?.let {
                        if (usbManager.hasPermission(it)) {
                            openAccessory(it)
                        } else {
                            requestAccessoryPermission(it)
                        }
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                    } else {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                    }
                    Log.i(TAG, "USB Accessory detached: ${accessory?.description}")
                    closeAccessory()
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    @Suppress("DEPRECATION")
                    val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                    } else {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                    }
                    if (granted && accessory != null) {
                        Log.i(TAG, "USB Accessory permission granted.")
                        openAccessory(accessory)
                    } else {
                        Log.w(TAG, "USB Accessory permission denied.")
                    }
                }
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true

        // Check if accessory is already attached
        checkAndOpenAttachedAccessory()
    }

    fun checkAndOpenAttachedAccessory(): Boolean {
        val accessoryList = usbManager.accessoryList
        if (accessoryList.isNullOrEmpty()) {
            Log.i(TAG, "No active USB accessories detected at startup. Waiting for attachment...")
            return false
        }

        val accessory = accessoryList[0]
        Log.i(TAG, "Found attached USB accessory: ${accessory.manufacturer} - ${accessory.model}")

        if (usbManager.hasPermission(accessory)) {
            return openAccessory(accessory)
        } else {
            return requestAccessoryPermission(accessory)
        }
    }

    private fun requestAccessoryPermission(accessory: UsbAccessory): Boolean {
        Log.i(TAG, "Requesting permission for USB accessory...")
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flag
        )
        usbManager.requestPermission(accessory, permissionIntent)
        return false
    }

    @Synchronized
    private fun openAccessory(accessory: UsbAccessory): Boolean {
        if (isConnected) return true

        if (!usbManager.hasPermission(accessory)) {
            requestAccessoryPermission(accessory)
            return false
        }

        try {
            val pfd = usbManager.openAccessory(accessory)
            if (pfd == null) {
                Log.e(TAG, "usbManager.openAccessory returned null")
                return false
            }

            fileDescriptor = pfd
            val fd = pfd.fileDescriptor
            val out = FileOutputStream(fd)
            val inStream = FileInputStream(fd)
            outputStream = out
            inputStream = inStream
            isConnected = true
            Log.i(TAG, "Successfully opened USB Accessory FileOutputStream and FileInputStream.")

            // Start zero-queue sender with active stream
            latestConfigHeader?.let { config ->
                sender.sendConfig(config)
            }
            sender.start(out)

            // Start background reader for host signals (e.g. keyframe requests)
            readThread = thread(name = "AoaAccessoryReadThread") {
                listenHostSignals()
            }

            onAccessoryConnected?.invoke()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open USB Accessory: ${e.message}", e)
            closeAccessory()
            return false
        }
    }

    /**
     * Sends the cached SPS/PPS configuration bytes as the very first packet to Phone B.
     */
    fun sendCachedConfigIfAvailable(): Boolean {
        val config = latestConfigHeader ?: return false
        sender.sendConfig(config)
        return true
    }

    private fun listenHostSignals() {
        val inStream = inputStream ?: return
        val buffer = ByteArray(64)
        while (isRunning && isConnected) {
            try {
                val read = inStream.read(buffer)
                if (read == -1) break
                if (read >= 3) {
                    // Check if host sent a heartbeat or request
                    if (buffer[0] == FramingProtocol.MAGIC_0 && buffer[1] == FramingProtocol.MAGIC_1) {
                        val type = buffer[2]
                        if (type == FramingProtocol.TYPE_HEARTBEAT || type == FramingProtocol.TYPE_CONFIG) {
                            Log.i(TAG, "Host requested sync frame (keyframe)")
                            onKeyframeRequested?.invoke()
                        }
                    }
                }
            } catch (e: IOException) {
                if (isConnected) {
                    Log.w(TAG, "Accessory input read exception: ${e.message}")
                }
                break
            }
        }
    }

    /**
     * Send codec configuration (SPS / PPS) packet.
     */
    fun sendConfig(configData: ByteArray) {
        latestConfigHeader = configData.copyOf()
        sender.sendConfig(configData)
    }

    /**
     * Send sleep/wake state packet to host.
     */
    fun sendSleepState(isAsleep: Boolean) {
        sender.sendSleepState(isAsleep)
    }

    /**
     * Send video frame / NAL unit packet using zero-queue drop-tail architecture.
     */
    fun sendFrame(
        type: Byte,
        flags: Byte,
        ptsUs: Long,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size
    ) {
        sender.sendFrame(type, flags, ptsUs, payload, offset, length)
    }

    @Synchronized
    fun closeAccessory() {
        if (!isConnected && fileDescriptor == null) return
        isConnected = false

        sender.stop()

        try {
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null

        try {
            inputStream?.close()
        } catch (_: Exception) {}
        inputStream = null

        try {
            fileDescriptor?.close()
        } catch (_: Exception) {}
        fileDescriptor = null

        readThread?.interrupt()
        readThread = null

        Log.i(TAG, "USB Accessory closed.")
        onAccessoryDisconnected?.invoke()
    }

    fun stop() {
        isRunning = false
        closeAccessory()

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
