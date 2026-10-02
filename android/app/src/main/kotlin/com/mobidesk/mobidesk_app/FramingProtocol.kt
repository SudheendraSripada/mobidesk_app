package com.mobidesk.mobidesk_app

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight binary framing protocol for USB Bulk streaming.
 *
 * Header layout (16 bytes):
 * - [0..1]  Magic bytes: 0x4D, 0x42 ("MB")
 * - [2]     Packet Type: 1 = CONFIG (SPS/PPS), 2 = FRAME (Video NAL), 3 = HEARTBEAT
 * - [3]     Flags: 0x01 = KEY_FRAME, 0x00 = NORMAL
 * - [4..7]  Payload Length (32-bit big-endian integer)
 * - [8..15] Presentation Time Stamp in microseconds (64-bit big-endian long)
 *
 * Followed immediately by [Payload Length] bytes of payload data.
 */
object FramingProtocol {
    const val MAGIC_0: Byte = 0x4D.toByte() // 'M'
    const val MAGIC_1: Byte = 0x42.toByte() // 'B'
    const val HEADER_SIZE = 16

    const val TYPE_CONFIG: Byte = 1
    const val TYPE_FRAME: Byte = 2
    const val TYPE_HEARTBEAT: Byte = 3
    const val TYPE_SLEEP: Byte = 4
    const val TYPE_DISPLAY_INFO: Byte = 5

    const val FLAG_NONE: Byte = 0x00
    const val FLAG_KEYFRAME: Byte = 0x01

    fun createHeader(type: Byte, flags: Byte, payloadLength: Int, ptsUs: Long): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC_0)
        buffer.put(MAGIC_1)
        buffer.put(type)
        buffer.put(flags)
        buffer.putInt(payloadLength)
        buffer.putLong(ptsUs)
        return buffer.array()
    }

    /**
     * Serializes header and payload onto the provided OutputStream.
     */
    @Synchronized
    fun writeFrame(
        out: OutputStream,
        type: Byte,
        flags: Byte,
        payload: ByteArray,
        offset: Int,
        length: Int,
        ptsUs: Long
    ) {
        val header = createHeader(type, flags, length, ptsUs)
        out.write(header)
        if (length > 0) {
            out.write(payload, offset, length)
        }
        out.flush()
    }

    /**
     * Creates a 12-byte binary payload containing monitor display dimensions and refresh rate.
     */
    fun createDisplayInfoPayload(width: Int, height: Int, fps: Int): ByteArray {
        val buffer = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(width)
        buffer.putInt(height)
        buffer.putInt(fps)
        return buffer.array()
    }

    /**
     * Parses a 12-byte display info payload into width, height, and fps.
     */
    fun parseDisplayInfo(payload: ByteArray): Triple<Int, Int, Int> {
        if (payload.size < 12) {
            throw IllegalArgumentException("Payload too short for DisplayInfo: ${payload.size} bytes (required: 12)")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val width = buffer.int
        val height = buffer.int
        val fps = buffer.int
        return Triple(width, height, fps)
    }

    /**
     * Writes a TYPE_DISPLAY_INFO packet directly to an output stream.
     */
    @Synchronized
    fun writeDisplayInfo(out: OutputStream, width: Int, height: Int, fps: Int) {
        val payload = createDisplayInfoPayload(width, height, fps)
        writeFrame(out, TYPE_DISPLAY_INFO, FLAG_NONE, payload, 0, payload.size, 0L)
    }
}

