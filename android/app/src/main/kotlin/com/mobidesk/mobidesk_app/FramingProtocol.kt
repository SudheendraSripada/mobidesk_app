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
    const val TYPE_INPUT_MOUSE: Byte = 6
    const val TYPE_INPUT_KEY: Byte = 7

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

    /**
     * Creates an 8-byte binary payload for TYPE_INPUT_MOUSE.
     * Big-endian layout:
     * - [0..1] normX (uint16)
     * - [2..3] normY (uint16)
     * - [4]    buttonMask (uint8: bit 0 = Left, bit 1 = Middle, bit 2 = Right)
     * - [5]    wheelDx (int8)
     * - [6]    wheelDy (int8)
     * - [7]    reserved (uint8: 0)
     */
    fun createInputMousePayload(
        normX: Int,
        normY: Int,
        buttonMask: Int,
        wheelDx: Int = 0,
        wheelDy: Int = 0
    ): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(normX.toShort())
        buffer.putShort(normY.toShort())
        buffer.put((buttonMask and 0xFF).toByte())
        buffer.put((wheelDx and 0xFF).toByte())
        buffer.put((wheelDy and 0xFF).toByte())
        buffer.put(0.toByte()) // reserved
        return buffer.array()
    }

    /**
     * Parses an 8-byte TYPE_INPUT_MOUSE payload.
     */
    fun parseInputMouse(payload: ByteArray): InputMouseData {
        if (payload.size < 8) {
            throw IllegalArgumentException("Payload too short for InputMouse: ${payload.size} bytes (required: 8)")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val normX = buffer.short.toInt() and 0xFFFF
        val normY = buffer.short.toInt() and 0xFFFF
        val buttonMask = buffer.get().toInt() and 0xFF
        val wheelDx = buffer.get().toInt()
        val wheelDy = buffer.get().toInt()
        return InputMouseData(normX, normY, buttonMask, wheelDx, wheelDy)
    }

    /**
     * Writes a TYPE_INPUT_MOUSE packet directly to an output stream.
     */
    @Synchronized
    fun writeInputMouse(
        out: OutputStream,
        normX: Int,
        normY: Int,
        buttonMask: Int,
        wheelDx: Int = 0,
        wheelDy: Int = 0,
        ptsUs: Long = 0L
    ) {
        val payload = createInputMousePayload(normX, normY, buttonMask, wheelDx, wheelDy)
        writeFrame(out, TYPE_INPUT_MOUSE, FLAG_NONE, payload, 0, payload.size, ptsUs)
    }

    /**
     * Creates an 8-byte binary payload for TYPE_INPUT_KEY.
     * Big-endian layout:
     * - [0..3] keyCode (uint32)
     * - [4]    state (uint8: 1 = down, 0 = up)
     * - [5]    modifierMask (uint8: bit 0 = Shift, bit 1 = Ctrl, bit 2 = Alt, bit 3 = Meta)
     * - [6..7] reserved (uint16: 0)
     */
    fun createInputKeyPayload(
        keyCode: Int,
        state: Int,
        modifierMask: Int = 0
    ): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(keyCode)
        buffer.put((state and 0xFF).toByte())
        buffer.put((modifierMask and 0xFF).toByte())
        buffer.putShort(0.toShort()) // reserved
        return buffer.array()
    }

    /**
     * Parses an 8-byte TYPE_INPUT_KEY payload.
     */
    fun parseInputKey(payload: ByteArray): InputKeyData {
        if (payload.size < 8) {
            throw IllegalArgumentException("Payload too short for InputKey: ${payload.size} bytes (required: 8)")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val keyCode = buffer.int
        val state = buffer.get().toInt() and 0xFF
        val modifierMask = buffer.get().toInt() and 0xFF
        return InputKeyData(keyCode, state, modifierMask)
    }

    /**
     * Writes a TYPE_INPUT_KEY packet directly to an output stream.
     */
    @Synchronized
    fun writeInputKey(
        out: OutputStream,
        keyCode: Int,
        state: Int,
        modifierMask: Int = 0,
        ptsUs: Long = 0L
    ) {
        val payload = createInputKeyPayload(keyCode, state, modifierMask)
        writeFrame(out, TYPE_INPUT_KEY, FLAG_NONE, payload, 0, payload.size, ptsUs)
    }
}

data class InputMouseData(
    val normX: Int,
    val normY: Int,
    val buttonMask: Int,
    val wheelDx: Int = 0,
    val wheelDy: Int = 0
) {
    val isLeftDown: Boolean get() = (buttonMask and 0x01) != 0
    val isMiddleDown: Boolean get() = (buttonMask and 0x02) != 0
    val isRightDown: Boolean get() = (buttonMask and 0x04) != 0
}

data class InputKeyData(
    val keyCode: Int,
    val state: Int,
    val modifierMask: Int = 0
) {
    val isDown: Boolean get() = state != 0
    val isShiftDown: Boolean get() = (modifierMask and 0x01) != 0
    val isCtrlDown: Boolean get() = (modifierMask and 0x02) != 0
    val isAltDown: Boolean get() = (modifierMask and 0x04) != 0
    val isMetaDown: Boolean get() = (modifierMask and 0x08) != 0
}

