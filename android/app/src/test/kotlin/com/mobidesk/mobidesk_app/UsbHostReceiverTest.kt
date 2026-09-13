package com.mobidesk.mobidesk_app

import android.app.Activity
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHostReceiverTest {

    @Test
    fun testFindConnectedDeviceWithNullManagerReturnsNull() {
        val device = UsbHostReceiver.findConnectedDevice(null)
        assertNull(device)
    }

    @Test
    fun testHandleStartReceiverWithNullManagerReturnsCleanNoDeviceError() {
        var errorCode: String? = null
        var errorMessage: String? = null
        var errorDetails: Any? = null
        var successCalled = false

        val mockResult = object : MethodChannel.Result {
            override fun success(result: Any?) {
                successCalled = true
            }

            override fun error(code: String, message: String?, details: Any?) {
                errorCode = code
                errorMessage = message
                errorDetails = details
            }

            override fun notImplemented() {}
        }

        // Test with null usbManager: should return clean result.error("NO_DEVICE", ...)
        // without throwing exceptions or crashing
        UsbHostReceiver.handleStartReceiver(
            Activity(),
            null,
            mockResult
        )

        assertFalse("success should not be called", successCalled)
        assertEquals("NO_DEVICE", errorCode)
        assertEquals("Please connect Phone A using an OTG adapter", errorMessage)
        assertNull(errorDetails)
    }

    @Test
    fun testAoaAccessoryConstants() {
        assertEquals(0x18D1, UsbHostReceiver.AOA_VENDOR_ID)
        assertTrue(UsbHostReceiver.AOA_PRODUCT_IDS.contains(0x2D00))
        assertTrue(UsbHostReceiver.AOA_PRODUCT_IDS.contains(0x2D01))
        assertTrue(UsbHostReceiver.AOA_PRODUCT_IDS.contains(0x2D02))
        assertTrue(UsbHostReceiver.AOA_PRODUCT_IDS.contains(0x2D03))
        assertTrue(UsbHostReceiver.AOA_PRODUCT_IDS.contains(0x2D04))
        assertTrue(UsbHostReceiver.AOA_PRODUCT_IDS.contains(0x2D05))
        assertFalse(UsbHostReceiver.AOA_PRODUCT_IDS.contains(0x1234))

        assertEquals(51, UsbHostReceiver.AOA_GET_PROTOCOL)
        assertEquals(52, UsbHostReceiver.AOA_SEND_STRING)
        assertEquals(53, UsbHostReceiver.AOA_START_ACCESSORY)
    }
}
