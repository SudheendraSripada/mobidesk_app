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
}
