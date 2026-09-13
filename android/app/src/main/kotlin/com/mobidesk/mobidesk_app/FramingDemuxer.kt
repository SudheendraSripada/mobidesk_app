package com.mobidesk.mobidesk_app

import android.util.Log

/**
 * Background streaming demuxer for the binary framing protocol over USB Bulk IN.
 * Accumulates chunked byte streams, detects and resynchronizes to magic bytes (0x4D42),
 * validates framing integrity, and dispatches complete frames to the consumer.
 */
class FramingDemuxer(
    private val onFrameReceived: (type: Byte, flags: Byte, ptsUs: Long, payload: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "FramingDemuxer"
        private const val INITIAL_BUFFER_SIZE = 256 * 1024
        private const val MAX_FRAME_SIZE = 10 * 1024 * 1024 // 10MB safety limit
    }

    private var buffer = ByteArray(INITIAL_BUFFER_SIZE)
    private var bufferSize = 0

    @Synchronized
    fun feedData(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return

        // Expand accumulator buffer if necessary
        if (bufferSize + length > buffer.size) {
            var newCap = buffer.size * 2
            while (bufferSize + length > newCap) {
                newCap *= 2
            }
            buffer = buffer.copyOf(newCap)
        }

        System.arraycopy(data, offset, buffer, bufferSize, length)
        bufferSize += length

        var readPos = 0
        while (bufferSize - readPos >= FramingProtocol.HEADER_SIZE) {
            // Verify Magic bytes (0x4D, 0x42)
            if (buffer[readPos] != FramingProtocol.MAGIC_0 || buffer[readPos + 1] != FramingProtocol.MAGIC_1) {
                // Seek forward to next occurrence of 0x4D, 0x42 to resync
                var syncPos = -1
                for (i in readPos + 1 until bufferSize - 1) {
                    if (buffer[i] == FramingProtocol.MAGIC_0 && buffer[i + 1] == FramingProtocol.MAGIC_1) {
                        syncPos = i
                        break
                    }
                }

                if (syncPos != -1) {
                    readPos = syncPos
                } else {
                    // Check if the very last byte matches MAGIC_0
                    if (buffer[bufferSize - 1] == FramingProtocol.MAGIC_0) {
                        buffer[0] = FramingProtocol.MAGIC_0
                        bufferSize = 1
                    } else {
                        bufferSize = 0
                    }
                    return
                }
            }

            if (bufferSize - readPos < FramingProtocol.HEADER_SIZE) {
                break
            }

            val type = buffer[readPos + 2]
            val flags = buffer[readPos + 3]
            val payloadLen = ((buffer[readPos + 4].toInt() and 0xFF) shl 24) or
                             ((buffer[readPos + 5].toInt() and 0xFF) shl 16) or
                             ((buffer[readPos + 6].toInt() and 0xFF) shl 8) or
                             (buffer[readPos + 7].toInt() and 0xFF)

            if (payloadLen < 0 || payloadLen > MAX_FRAME_SIZE) {
                Log.w(TAG, "Demuxer encountered corrupted payload length: $payloadLen, attempting resync...")
                readPos += 2
                continue
            }

            val totalFrameSize = FramingProtocol.HEADER_SIZE + payloadLen
            if (bufferSize - readPos < totalFrameSize) {
                // Incomplete payload, wait for next USB chunk
                break
            }

            // Extract 64-bit Presentation Time Stamp (microseconds)
            var ptsUs = 0L
            for (i in 0 until 8) {
                ptsUs = (ptsUs shl 8) or (buffer[readPos + 8 + i].toLong() and 0xFFL)
            }

            val payload = ByteArray(payloadLen)
            if (payloadLen > 0) {
                System.arraycopy(buffer, readPos + FramingProtocol.HEADER_SIZE, payload, 0, payloadLen)
            }

            try {
                onFrameReceived(type, flags, ptsUs, payload)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onFrameReceived callback: ${e.message}", e)
            }

            readPos += totalFrameSize
        }

        // Shift remaining unparsed bytes to the beginning of the buffer
        if (readPos > 0) {
            val remaining = bufferSize - readPos
            if (remaining > 0) {
                System.arraycopy(buffer, readPos, buffer, 0, remaining)
            }
            bufferSize = remaining
        }
    }

    @Synchronized
    fun reset() {
        bufferSize = 0
    }
}

