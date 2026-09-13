package com.mobidesk.mobidesk_app

import android.content.Context

/**
 * Compatibility wrapper around UsbHostReceiver.
 */
class AoaHostManager(
    context: Context,
    onFrameReceived: (type: Byte, flags: Byte, ptsUs: Long, payload: ByteArray) -> Unit
) {
    private val receiver = UsbHostReceiver(context, null, onFrameReceived)

    var onBulkInReady: ((android.hardware.usb.UsbEndpoint) -> Unit)?
        get() = receiver.onBulkInReady
        set(value) { receiver.onBulkInReady = value }

    var onStatusChanged: ((String) -> Unit)?
        get() = receiver.onStatusChanged
        set(value) { receiver.onStatusChanged = value }

    var onError: ((String) -> Unit)?
        get() = receiver.onError
        set(value) { receiver.onError = value }

    var onConnected: (() -> Unit)?
        get() = receiver.onConnected
        set(value) { receiver.onConnected = value }

    var onDisconnected: (() -> Unit)?
        get() = receiver.onDisconnected
        set(value) { receiver.onDisconnected = value }

    fun start() {
        receiver.start()
    }

    fun stop() {
        receiver.stop()
    }

    fun scanDevices() {
        receiver.triggerScan()
    }

    fun requestKeyframeFromSender() {
        receiver.requestKeyframeFromSender()
    }
}
