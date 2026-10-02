package com.mobidesk.mobidesk_app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class FramingDemuxerTest {

    data class ReceivedFrame(
        val type: Byte,
        val flags: Byte,
        val ptsUs: Long,
        val payload: ByteArray
    )

    @Test
    fun testSingleCompleteFrame() {
        val frames = mutableListOf<ReceivedFrame>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            frames.add(ReceivedFrame(type, flags, ptsUs, payload))
        }

        val testPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val out = ByteArrayOutputStream()
        FramingProtocol.writeFrame(
            out,
            FramingProtocol.TYPE_FRAME,
            FramingProtocol.FLAG_KEYFRAME,
            testPayload,
            0,
            testPayload.size,
            123456789L
        )

        val packet = out.toByteArray()
        demuxer.feedData(packet, 0, packet.size)

        assertEquals(1, frames.size)
        assertEquals(FramingProtocol.TYPE_FRAME, frames[0].type)
        assertEquals(FramingProtocol.FLAG_KEYFRAME, frames[0].flags)
        assertEquals(123456789L, frames[0].ptsUs)
        assertArrayEquals(testPayload, frames[0].payload)
    }

    @Test
    fun testMultipleFramesInSingleChunk() {
        val frames = mutableListOf<ReceivedFrame>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            frames.add(ReceivedFrame(type, flags, ptsUs, payload))
        }

        val out = ByteArrayOutputStream()
        val payload1 = byteArrayOf(10, 20, 30)
        val payload2 = byteArrayOf(40, 50, 60, 70)

        FramingProtocol.writeFrame(out, FramingProtocol.TYPE_CONFIG, FramingProtocol.FLAG_KEYFRAME, payload1, 0, payload1.size, 100L)
        FramingProtocol.writeFrame(out, FramingProtocol.TYPE_FRAME, FramingProtocol.FLAG_NONE, payload2, 0, payload2.size, 200L)

        val allBytes = out.toByteArray()
        demuxer.feedData(allBytes, 0, allBytes.size)

        assertEquals(2, frames.size)
        assertEquals(FramingProtocol.TYPE_CONFIG, frames[0].type)
        assertArrayEquals(payload1, frames[0].payload)
        assertEquals(100L, frames[0].ptsUs)

        assertEquals(FramingProtocol.TYPE_FRAME, frames[1].type)
        assertArrayEquals(payload2, frames[1].payload)
        assertEquals(200L, frames[1].ptsUs)
    }

    @Test
    fun testFragmentedByteFeeding() {
        val frames = mutableListOf<ReceivedFrame>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            frames.add(ReceivedFrame(type, flags, ptsUs, payload))
        }

        val payload = ByteArray(100) { it.toByte() }
        val out = ByteArrayOutputStream()
        FramingProtocol.writeFrame(out, FramingProtocol.TYPE_FRAME, FramingProtocol.FLAG_KEYFRAME, payload, 0, payload.size, 999999L)
        val fullData = out.toByteArray()

        // Feed 1 byte at a time to test fragmentation tolerance
        for (b in fullData) {
            demuxer.feedData(byteArrayOf(b), 0, 1)
        }

        assertEquals(1, frames.size)
        assertEquals(FramingProtocol.TYPE_FRAME, frames[0].type)
        assertEquals(FramingProtocol.FLAG_KEYFRAME, frames[0].flags)
        assertEquals(999999L, frames[0].ptsUs)
        assertArrayEquals(payload, frames[0].payload)
    }

    @Test
    fun testCorruptedBytesBeforeValidFrameResynchronization() {
        val frames = mutableListOf<ReceivedFrame>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            frames.add(ReceivedFrame(type, flags, ptsUs, payload))
        }

        val testPayload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val out = ByteArrayOutputStream()
        // Write some junk bytes before the valid frame
        out.write(byteArrayOf(0x00, 0x11, 0x22, 0x4D, 0x00, 0x33, 0x44))

        FramingProtocol.writeFrame(
            out,
            FramingProtocol.TYPE_FRAME,
            FramingProtocol.FLAG_NONE,
            testPayload,
            0,
            testPayload.size,
            55555L
        )

        val streamWithGarbage = out.toByteArray()
        demuxer.feedData(streamWithGarbage, 0, streamWithGarbage.size)

        assertEquals(1, frames.size)
        assertEquals(FramingProtocol.TYPE_FRAME, frames[0].type)
        assertEquals(55555L, frames[0].ptsUs)
        assertArrayEquals(testPayload, frames[0].payload)
    }

    @Test
    fun testEmptyPayloadFrame() {
        val frames = mutableListOf<ReceivedFrame>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            frames.add(ReceivedFrame(type, flags, ptsUs, payload))
        }

        val out = ByteArrayOutputStream()
        FramingProtocol.writeFrame(
            out,
            FramingProtocol.TYPE_HEARTBEAT,
            FramingProtocol.FLAG_NONE,
            ByteArray(0),
            0,
            0,
            123L
        )

        val data = out.toByteArray()
        demuxer.feedData(data, 0, data.size)

        assertEquals(1, frames.size)
        assertEquals(FramingProtocol.TYPE_HEARTBEAT, frames[0].type)
        assertEquals(0, frames[0].payload.size)
        assertEquals(123L, frames[0].ptsUs)
    }

    @Test
    fun testResetClearsBuffer() {
        val frames = mutableListOf<ReceivedFrame>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, payload ->
            frames.add(ReceivedFrame(type, flags, ptsUs, payload))
        }

        // Feed incomplete header
        demuxer.feedData(byteArrayOf(FramingProtocol.MAGIC_0, FramingProtocol.MAGIC_1, 1), 0, 3)
        demuxer.reset()

        // Feed full valid frame after reset
        val out = ByteArrayOutputStream()
        val payload = byteArrayOf(1, 2)
        FramingProtocol.writeFrame(out, FramingProtocol.TYPE_FRAME, FramingProtocol.FLAG_NONE, payload, 0, payload.size, 1L)
        val data = out.toByteArray()
        demuxer.feedData(data, 0, data.size)

        assertEquals(1, frames.size)
        assertArrayEquals(payload, frames[0].payload)
    }

    @Test
    fun testTypeDisplayInfoRoundTrip() {
        assertEquals(5.toByte(), FramingProtocol.TYPE_DISPLAY_INFO)

        val width = 1920
        val height = 1080
        val fps = 60

        val payload = FramingProtocol.createDisplayInfoPayload(width, height, fps)
        assertEquals(12, payload.size)

        val (parsedW, parsedH, parsedFps) = FramingProtocol.parseDisplayInfo(payload)
        assertEquals(width, parsedW)
        assertEquals(height, parsedH)
        assertEquals(fps, parsedFps)

        // Test streaming through FramingDemuxer
        val frames = mutableListOf<ReceivedFrame>()
        val demuxer = FramingDemuxer { type, flags, ptsUs, receivedPayload ->
            frames.add(ReceivedFrame(type, flags, ptsUs, receivedPayload))
        }

        val out = ByteArrayOutputStream()
        FramingProtocol.writeDisplayInfo(out, width, height, fps)

        val packet = out.toByteArray()
        demuxer.feedData(packet, 0, packet.size)

        assertEquals(1, frames.size)
        assertEquals(FramingProtocol.TYPE_DISPLAY_INFO, frames[0].type)
        assertEquals(FramingProtocol.FLAG_NONE, frames[0].flags)
        assertEquals(12, frames[0].payload.size)

        val demuxed = FramingProtocol.parseDisplayInfo(frames[0].payload)
        assertEquals(1920, demuxed.first)
        assertEquals(1080, demuxed.second)
        assertEquals(60, demuxed.third)
    }

    @Test
    fun testTypeInputMouseRoundTrip() {
        assertEquals(6.toByte(), FramingProtocol.TYPE_INPUT_MOUSE)

        val payload = FramingProtocol.createInputMousePayload(
            normX = 32768,
            normY = 16384,
            buttonMask = 1,
            wheelDx = 0,
            wheelDy = -1
        )
        assertEquals(8, payload.size)

        val mouse = FramingProtocol.parseInputMouse(payload)
        assertEquals(32768, mouse.normX)
        assertEquals(16384, mouse.normY)
        assertEquals(1, mouse.buttonMask)
        assertTrue(mouse.isLeftDown)
        assertEquals(0, mouse.wheelDx)
        assertEquals(-1, mouse.wheelDy)

        val out = ByteArrayOutputStream()
        FramingProtocol.writeInputMouse(out, 32768, 16384, 1, 0, -1, 50000L)
        val packet = out.toByteArray()
        assertEquals(24, packet.size)
        assertEquals("4d42060000000008000000000000c350800040000100ff00", packet.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun testTypeInputKeyRoundTrip() {
        assertEquals(7.toByte(), FramingProtocol.TYPE_INPUT_KEY)

        val payload = FramingProtocol.createInputKeyPayload(
            keyCode = 28,
            state = 1,
            modifierMask = 2
        )
        assertEquals(8, payload.size)

        val key = FramingProtocol.parseInputKey(payload)
        assertEquals(28, key.keyCode)
        assertEquals(1, key.state)
        assertEquals(2, key.modifierMask)
        assertTrue(key.isDown)
        assertTrue(key.isCtrlDown)

        val out = ByteArrayOutputStream()
        FramingProtocol.writeInputKey(out, 28, 1, 2, 75000L)
        val packet = out.toByteArray()
        assertEquals(24, packet.size)
        assertEquals("4d4207000000000800000000000124f80000001c01020000", packet.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun testGoldenVectorsExactHex() {
        fun hexToBytes(hex: String): ByteArray {
            val len = hex.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        // 1. CONFIG
        val configPayload = hexToBytes("000000016742001f")
        val configOut = ByteArrayOutputStream()
        FramingProtocol.writeFrame(configOut, FramingProtocol.TYPE_CONFIG, FramingProtocol.FLAG_KEYFRAME, configPayload, 0, configPayload.size, 1000L)
        assertEquals("4d4201010000000800000000000003e8000000016742001f", bytesToHex(configOut.toByteArray()))

        // 2. FRAME
        val framePayload = hexToBytes("00000001419a2401")
        val frameOut = ByteArrayOutputStream()
        FramingProtocol.writeFrame(frameOut, FramingProtocol.TYPE_FRAME, FramingProtocol.FLAG_NONE, framePayload, 0, framePayload.size, 33333L)
        assertEquals("4d42020000000008000000000000823500000001419a2401", bytesToHex(frameOut.toByteArray()))

        // 3. HEARTBEAT
        val hbOut = ByteArrayOutputStream()
        FramingProtocol.writeFrame(hbOut, FramingProtocol.TYPE_HEARTBEAT, FramingProtocol.FLAG_KEYFRAME, ByteArray(0), 0, 0, 0L)
        assertEquals("4d420301000000000000000000000000", bytesToHex(hbOut.toByteArray()))

        // 4. SLEEP
        val sleepOut = ByteArrayOutputStream()
        val sleepPayload = byteArrayOf(1)
        FramingProtocol.writeFrame(sleepOut, FramingProtocol.TYPE_SLEEP, FramingProtocol.FLAG_NONE, sleepPayload, 0, 1, 0L)
        assertEquals("4d42040000000001000000000000000001", bytesToHex(sleepOut.toByteArray()))

        // 5. DISPLAY_INFO
        val dispOut = ByteArrayOutputStream()
        FramingProtocol.writeDisplayInfo(dispOut, 1920, 1080, 60)
        assertEquals("4d4205000000000c000000000000000000000780000004380000003c", bytesToHex(dispOut.toByteArray()))

        // 6. INPUT_MOUSE
        val mouseOut = ByteArrayOutputStream()
        FramingProtocol.writeInputMouse(mouseOut, 32768, 16384, 1, 0, -1, 50000L)
        assertEquals("4d42060000000008000000000000c350800040000100ff00", bytesToHex(mouseOut.toByteArray()))

        // 7. INPUT_KEY
        val keyOut = ByteArrayOutputStream()
        FramingProtocol.writeInputKey(keyOut, 28, 1, 2, 75000L)
        assertEquals("4d4207000000000800000000000124f80000001c01020000", bytesToHex(keyOut.toByteArray()))
    }
}
