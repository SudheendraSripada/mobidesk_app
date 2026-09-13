package com.mobidesk.mobidesk_app

import android.app.Activity
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

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

    @Test
    fun testIsAccessory() {
        // Valid Google AOA VID and PIDs
        assertTrue(UsbHostReceiver.isAccessory(0x18D1, 0x2D00))
        assertTrue(UsbHostReceiver.isAccessory(0x18D1, 0x2D01))
        assertTrue(UsbHostReceiver.isAccessory(0x18D1, 0x2D02))
        assertTrue(UsbHostReceiver.isAccessory(0x18D1, 0x2D03))
        assertTrue(UsbHostReceiver.isAccessory(0x18D1, 0x2D04))
        assertTrue(UsbHostReceiver.isAccessory(0x18D1, 0x2D05))

        // Non-matching vendor ID
        assertFalse(UsbHostReceiver.isAccessory(0x04e8, 0x2D00))
        assertFalse(UsbHostReceiver.isAccessory(0x1234, 0x2D00))

        // Non-matching product ID with Google vendor
        assertFalse(UsbHostReceiver.isAccessory(0x18D1, 0x4ee1))
        assertFalse(UsbHostReceiver.isAccessory(0x18D1, 0x0000))
    }

    @Test
    fun testFramingProtocolTypesAndHeartbeat() {
        assertEquals(1.toByte(), FramingProtocol.TYPE_CONFIG)
        assertEquals(2.toByte(), FramingProtocol.TYPE_FRAME)
        assertEquals(3.toByte(), FramingProtocol.TYPE_HEARTBEAT)
        assertEquals(0x01.toByte(), FramingProtocol.FLAG_KEYFRAME)
        assertEquals(0x00.toByte(), FramingProtocol.FLAG_NONE)

        val header = FramingProtocol.createHeader(
            FramingProtocol.TYPE_HEARTBEAT,
            FramingProtocol.FLAG_KEYFRAME,
            0,
            123456L
        )
        assertEquals(FramingProtocol.HEADER_SIZE, header.size)
        assertEquals(0x4D.toByte(), header[0]) // 'M'
        assertEquals(0x42.toByte(), header[1]) // 'B'
        assertEquals(FramingProtocol.TYPE_HEARTBEAT, header[2])
        assertEquals(FramingProtocol.FLAG_KEYFRAME, header[3])
    }

    @Test
    fun testQuadrupleDataClass() {
        val quad = Quadruple("A", 1, true, "D")
        assertEquals("A", quad.first)
        assertEquals(1, quad.second)
        assertEquals(true, quad.third)
        assertEquals("D", quad.fourth)
    }

    @Test
    fun testDemuxerSingleFrame() {
        val receivedFrames = mutableListOf<Quadruple<Byte, Byte, Long, ByteArray>>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            receivedFrames.add(Quadruple(type, flags, ptsUs, payload))
        }

        val payload = byteArrayOf(10, 20, 30, 40, 50)
        val header = FramingProtocol.createHeader(
            FramingProtocol.TYPE_FRAME,
            FramingProtocol.FLAG_KEYFRAME,
            payload.size,
            999888777L
        )

        val stream = ByteArrayOutputStream()
        stream.write(header)
        stream.write(payload)
        val packet = stream.toByteArray()

        demuxer.feedData(packet, 0, packet.size)

        assertEquals(1, receivedFrames.size)
        val frame = receivedFrames[0]
        assertEquals(FramingProtocol.TYPE_FRAME, frame.first)
        assertEquals(FramingProtocol.FLAG_KEYFRAME, frame.second)
        assertEquals(999888777L, frame.third)
        assertArrayEquals(payload, frame.fourth)
    }

    @Test
    fun testDemuxerMultipleFramesInSingleChunk() {
        val receivedFrames = mutableListOf<Quadruple<Byte, Byte, Long, ByteArray>>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            receivedFrames.add(Quadruple(type, flags, ptsUs, payload))
        }

        val payload1 = byteArrayOf(1, 2, 3)
        val header1 = FramingProtocol.createHeader(
            FramingProtocol.TYPE_CONFIG,
            FramingProtocol.FLAG_NONE,
            payload1.size,
            100L
        )

        val payload2 = byteArrayOf(4, 5, 6, 7)
        val header2 = FramingProtocol.createHeader(
            FramingProtocol.TYPE_FRAME,
            FramingProtocol.FLAG_KEYFRAME,
            payload2.size,
            200L
        )

        val stream = ByteArrayOutputStream()
        stream.write(header1)
        stream.write(payload1)
        stream.write(header2)
        stream.write(payload2)
        val combined = stream.toByteArray()

        demuxer.feedData(combined, 0, combined.size)

        assertEquals(2, receivedFrames.size)
        assertEquals(FramingProtocol.TYPE_CONFIG, receivedFrames[0].first)
        assertEquals(100L, receivedFrames[0].third)
        assertArrayEquals(payload1, receivedFrames[0].fourth)

        assertEquals(FramingProtocol.TYPE_FRAME, receivedFrames[1].first)
        assertEquals(200L, receivedFrames[1].third)
        assertArrayEquals(payload2, receivedFrames[1].fourth)
    }

    @Test
    fun testDemuxerFragmentedByteStream() {
        val receivedFrames = mutableListOf<Quadruple<Byte, Byte, Long, ByteArray>>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            receivedFrames.add(Quadruple(type, flags, ptsUs, payload))
        }

        val payload = "Hello MobiDesk Framing Stream".toByteArray(Charsets.UTF_8)
        val header = FramingProtocol.createHeader(
            FramingProtocol.TYPE_FRAME,
            FramingProtocol.FLAG_NONE,
            payload.size,
            555123L
        )

        val stream = ByteArrayOutputStream()
        stream.write(header)
        stream.write(payload)
        val packet = stream.toByteArray()

        // Feed 1 byte at a time to test fragmentation handling
        for (i in packet.indices) {
            demuxer.feedData(byteArrayOf(packet[i]), 0, 1)
        }

        assertEquals(1, receivedFrames.size)
        assertEquals(FramingProtocol.TYPE_FRAME, receivedFrames[0].first)
        assertEquals(555123L, receivedFrames[0].third)
        assertArrayEquals(payload, receivedFrames[0].fourth)
    }

    @Test
    fun testDemuxerResynchronizesGarbageBytes() {
        val receivedFrames = mutableListOf<Quadruple<Byte, Byte, Long, ByteArray>>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            receivedFrames.add(Quadruple(type, flags, ptsUs, payload))
        }

        val garbage = byteArrayOf(0x00, 0x11, 0x22, 0x4D, 0x00, 0x7F, 0x33)
        val payload = byteArrayOf(42, 84)
        val header = FramingProtocol.createHeader(
            FramingProtocol.TYPE_FRAME,
            FramingProtocol.FLAG_KEYFRAME,
            payload.size,
            777L
        )

        val stream = ByteArrayOutputStream()
        stream.write(garbage)
        stream.write(header)
        stream.write(payload)
        val dataWithGarbage = stream.toByteArray()

        demuxer.feedData(dataWithGarbage, 0, dataWithGarbage.size)

        assertEquals(1, receivedFrames.size)
        assertEquals(FramingProtocol.TYPE_FRAME, receivedFrames[0].first)
        assertEquals(777L, receivedFrames[0].third)
        assertArrayEquals(payload, receivedFrames[0].fourth)
    }

    @Test
    fun testDemuxerReset() {
        val receivedFrames = mutableListOf<Quadruple<Byte, Byte, Long, ByteArray>>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            receivedFrames.add(Quadruple(type, flags, ptsUs, payload))
        }

        val header = FramingProtocol.createHeader(
            FramingProtocol.TYPE_FRAME,
            FramingProtocol.FLAG_NONE,
            100, // declares 100 bytes payload
            123L
        )
        // Feed only header (incomplete frame)
        demuxer.feedData(header, 0, header.size)
        assertEquals(0, receivedFrames.size)

        // Reset demuxer
        demuxer.reset()

        // Feed a complete new frame
        val completePayload = byteArrayOf(9, 8, 7)
        val completeHeader = FramingProtocol.createHeader(
            FramingProtocol.TYPE_CONFIG,
            FramingProtocol.FLAG_KEYFRAME,
            completePayload.size,
            456L
        )
        val stream = ByteArrayOutputStream()
        stream.write(completeHeader)
        stream.write(completePayload)
        val fullPacket = stream.toByteArray()

        demuxer.feedData(fullPacket, 0, fullPacket.size)

        assertEquals(1, receivedFrames.size)
        assertEquals(FramingProtocol.TYPE_CONFIG, receivedFrames[0].first)
        assertArrayEquals(completePayload, receivedFrames[0].fourth)
    }

    @Test
    fun testReceiverActivityConstants() {
        assertEquals("extra_host", ReceiverActivity.EXTRA_HOST)
        assertEquals("extra_port", ReceiverActivity.EXTRA_PORT)
    }

    @Test
    fun testReceiverActivityApplyFullScreenDefensive() {
        val activity = ReceiverActivity()
        // Must not throw NullPointerException or any exception when window/decorView is uninitialized
        activity.applyFullScreen()
    }

    @Test
    fun testReceiverActivityAdjustSurfaceAspectRatioGuards() {
        val activity = ReceiverActivity()
        // Must not throw UninitializedPropertyAccessException or any exception when views are uninitialized
        activity.adjustSurfaceAspectRatio(720, 1280)
    }
}
