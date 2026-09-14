package com.mobidesk.mobidesk_app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UsbAccessorySenderTest {

    @Test
    fun testFramingProtocolTypeSleepConstant() {
        assertEquals(4.toByte(), FramingProtocol.TYPE_SLEEP)

        val header = FramingProtocol.createHeader(
            FramingProtocol.TYPE_SLEEP,
            FramingProtocol.FLAG_NONE,
            1,
            12345L
        )
        assertEquals(FramingProtocol.HEADER_SIZE, header.size)
        assertEquals(FramingProtocol.TYPE_SLEEP, header[2])
        assertEquals(FramingProtocol.FLAG_NONE, header[3])
    }

    @Test
    fun testUsbAccessorySenderInitialState() {
        val sender = UsbAccessorySender()
        assertFalse("Sender should not be connected initially", sender.isConnected)
        assertFalse("configSent should be false initially", sender.configSent)
    }

    @Test
    fun testUsbAccessorySenderCachedConfigTransmittedOnStart() {
        val sender = UsbAccessorySender()
        val dummyConfig = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E)
        sender.sendConfig(dummyConfig)

        val out = ByteArrayOutputStream()
        val receivedFrames = mutableListOf<Quadruple<Byte, Byte, Long, ByteArray>>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            receivedFrames.add(Quadruple(type, flags, ptsUs, payload))
        }

        sender.start(out)
        // Allow write worker thread to process
        Thread.sleep(100)
        val isConfigSent = sender.configSent
        sender.stop()

        val writtenBytes = out.toByteArray()
        assertTrue("Sender must have written bytes to stream", writtenBytes.isNotEmpty())
        demuxer.feedData(writtenBytes, 0, writtenBytes.size)

        assertEquals(1, receivedFrames.size)
        assertEquals(FramingProtocol.TYPE_CONFIG, receivedFrames[0].first)
        assertArrayEquals(dummyConfig, receivedFrames[0].fourth)
        assertTrue("configSent must be true", isConfigSent)
    }

    @Test
    fun testUsbAccessorySenderSleepStatePacket() {
        val sender = UsbAccessorySender()
        val out = ByteArrayOutputStream()
        val receivedFrames = mutableListOf<Quadruple<Byte, Byte, Long, ByteArray>>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            receivedFrames.add(Quadruple(type, flags, ptsUs, payload))
        }

        sender.start(out)
        sender.sendSleepState(true) // sleep
        sender.sendSleepState(false) // wake
        Thread.sleep(100)
        sender.stop()

        val writtenBytes = out.toByteArray()
        demuxer.feedData(writtenBytes, 0, writtenBytes.size)

        assertEquals(2, receivedFrames.size)
        assertEquals(FramingProtocol.TYPE_SLEEP, receivedFrames[0].first)
        assertEquals(1.toByte(), receivedFrames[0].fourth[0])

        assertEquals(FramingProtocol.TYPE_SLEEP, receivedFrames[1].first)
        assertEquals(0.toByte(), receivedFrames[1].fourth[0])
    }

    @Test
    fun testUsbAccessorySenderZeroQueueDropsStaleFrames() {
        // When multiple frames are queued while write worker is slow or simulated,
        // intermediate non-keyframes must be dropped, keeping only freshest frame.
        val sender = UsbAccessorySender()
        val blockingStream = object : ByteArrayOutputStream() {
            var blockWrites = true
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (blockWrites) {
                    Thread.sleep(200)
                }
                super.write(b, off, len)
            }
        }

        val receivedFrames = mutableListOf<Quadruple<Byte, Byte, Long, ByteArray>>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            receivedFrames.add(Quadruple(type, flags, ptsUs, payload))
        }

        sender.start(blockingStream)

        // Rapidly push multiple video frames
        for (i in 1..10) {
            val payload = byteArrayOf(i.toByte())
            sender.sendFrame(
                FramingProtocol.TYPE_FRAME,
                FramingProtocol.FLAG_NONE,
                i.toLong() * 1000L,
                payload
            )
        }

        blockingStream.blockWrites = false
        Thread.sleep(500)
        sender.stop()

        val writtenBytes = blockingStream.toByteArray()
        demuxer.feedData(writtenBytes, 0, writtenBytes.size)

        // Due to zero-queue dropping of older non-keyframes, received frames should be far fewer than 10
        assertTrue(
            "Stale frames should have been dropped (received ${receivedFrames.size} of 10)",
            receivedFrames.size < 10
        )
        // The last frame received should be the freshest frame (frame 10)
        val lastFrame = receivedFrames.last()
        assertEquals(10.toByte(), lastFrame.fourth[0])
    }

    @Test
    fun testUsbAccessorySenderKeyframeReplacesPendingFrame() {
        val sender = UsbAccessorySender()
        val blockingStream = object : ByteArrayOutputStream() {
            var block = true
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (block) Thread.sleep(150)
                super.write(b, off, len)
            }
        }
        val receivedFrames = mutableListOf<Quadruple<Byte, Byte, Long, ByteArray>>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            receivedFrames.add(Quadruple(type, flags, ptsUs, payload))
        }

        sender.start(blockingStream)

        // Enqueue non-keyframe
        sender.sendFrame(FramingProtocol.TYPE_FRAME, FramingProtocol.FLAG_NONE, 100L, byteArrayOf(1))
        // Immediately enqueue keyframe which must replace any pending non-keyframe
        sender.sendFrame(FramingProtocol.TYPE_FRAME, FramingProtocol.FLAG_KEYFRAME, 200L, byteArrayOf(2))

        blockingStream.block = false
        Thread.sleep(400)
        sender.stop()

        val writtenBytes = blockingStream.toByteArray()
        demuxer.feedData(writtenBytes, 0, writtenBytes.size)

        // The keyframe must have been delivered
        val hasKeyframe = receivedFrames.any { it.second == FramingProtocol.FLAG_KEYFRAME }
        assertTrue("Keyframe must be delivered", hasKeyframe)
    }

    @Test
    fun testUsbAccessorySenderPendingKeyframeNotOverwrittenByNonKeyframe() {
        val sender = UsbAccessorySender()
        val blockingStream = object : ByteArrayOutputStream() {
            var block = true
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (block) Thread.sleep(150)
                super.write(b, off, len)
            }
        }
        val receivedFrames = mutableListOf<Quadruple<Byte, Byte, Long, ByteArray>>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            receivedFrames.add(Quadruple(type, flags, ptsUs, payload))
        }

        sender.start(blockingStream)

        // Enqueue critical keyframe first
        val keyframePayload = byteArrayOf(42, 43, 44)
        sender.sendFrame(FramingProtocol.TYPE_FRAME, FramingProtocol.FLAG_KEYFRAME, 1000L, keyframePayload)

        // Immediately enqueue non-keyframe (P-frame) while write loop is delayed
        val pFramePayload = byteArrayOf(99, 99)
        sender.sendFrame(FramingProtocol.TYPE_FRAME, FramingProtocol.FLAG_NONE, 2000L, pFramePayload)

        blockingStream.block = false
        Thread.sleep(300)
        sender.stop()

        val writtenBytes = blockingStream.toByteArray()
        demuxer.feedData(writtenBytes, 0, writtenBytes.size)

        // The keyframe MUST be preserved and delivered; non-keyframe must NOT overwrite keyframe
        assertTrue("At least one frame should be delivered", receivedFrames.isNotEmpty())
        val deliveredVideoFrame = receivedFrames.firstOrNull { it.first == FramingProtocol.TYPE_FRAME }
        org.junit.Assert.assertNotNull("A video frame must have been delivered", deliveredVideoFrame)
        assertEquals(FramingProtocol.FLAG_KEYFRAME, deliveredVideoFrame!!.second)
        assertArrayEquals(keyframePayload, deliveredVideoFrame.fourth)
    }

    @Test
    fun testUsbAccessorySenderCachedKeyframePreloadedOnStart() {
        val sender = UsbAccessorySender()
        val stream1 = ByteArrayOutputStream()
        sender.start(stream1)

        val configPayload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67)
        sender.sendConfig(configPayload)

        val keyframePayload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x01, 0x02)
        sender.sendFrame(FramingProtocol.TYPE_FRAME, FramingProtocol.FLAG_KEYFRAME, 100L, keyframePayload)
        Thread.sleep(100)
        sender.stop()

        // Reconnect: Start new session with stream2
        val stream2 = ByteArrayOutputStream()
        val receivedFrames = mutableListOf<Quadruple<Byte, Byte, Long, ByteArray>>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            receivedFrames.add(Quadruple(type, flags, ptsUs, payload))
        }

        sender.start(stream2)
        Thread.sleep(150)
        sender.stop()

        val writtenBytes = stream2.toByteArray()
        demuxer.feedData(writtenBytes, 0, writtenBytes.size)

        // Stream 2 should immediately receive cached config followed by cached keyframe
        assertEquals(2, receivedFrames.size)
        assertEquals(FramingProtocol.TYPE_CONFIG, receivedFrames[0].first)
        assertArrayEquals(configPayload, receivedFrames[0].fourth)

        assertEquals(FramingProtocol.TYPE_FRAME, receivedFrames[1].first)
        assertEquals(FramingProtocol.FLAG_KEYFRAME, receivedFrames[1].second)
        assertArrayEquals(keyframePayload, receivedFrames[1].fourth)
    }

    @Test
    fun testUsbAccessorySenderOffsetAndLengthSlicing() {
        val sender = UsbAccessorySender()
        val out = ByteArrayOutputStream()
        val receivedFrames = mutableListOf<Quadruple<Byte, Byte, Long, ByteArray>>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            receivedFrames.add(Quadruple(type, flags, ptsUs, payload))
        }

        sender.start(out)
        val rawArray = byteArrayOf(99, 1, 2, 3, 99)
        // Send slice: offset 1, length 3 -> [1, 2, 3]
        sender.sendFrame(FramingProtocol.TYPE_FRAME, FramingProtocol.FLAG_KEYFRAME, 555L, rawArray, 1, 3)
        Thread.sleep(100)
        sender.stop()

        demuxer.feedData(out.toByteArray(), 0, out.size())
        assertEquals(1, receivedFrames.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), receivedFrames[0].fourth)
    }

    @Test
    fun testUsbAccessorySenderIdempotentStopAndUnstartedSafety() {
        val sender = UsbAccessorySender()
        // Unstarted sender should not crash on any call
        sender.sendFrame(FramingProtocol.TYPE_FRAME, FramingProtocol.FLAG_NONE, 0L, byteArrayOf(1, 2))
        sender.sendSleepState(true)
        sender.stop()
        sender.stop()
        assertFalse(sender.isConnected)
    }
}
