package com.mobidesk.mobidesk_app

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * TCP Client that connects to the host (default IP 192.168.42.129 on port 8888,
 * the standard Android USB tethering gateway) on a background thread and reads
 * incoming raw H.264 NAL units continuously from the socket.
 */
class StreamClient(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT
) {

    companion object {
        private const val TAG = "StreamClient"
        const val DEFAULT_HOST = "192.168.42.129"
        const val DEFAULT_PORT = 8888
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_BUFFER_SIZE = 64 * 1024
        private const val INITIAL_ACCUMULATOR_SIZE = 256 * 1024
    }

    @Volatile
    private var isRunning = false
    private var clientThread: Thread? = null
    private var socket: Socket? = null

    /**
     * Callback invoked whenever a complete H.264 NAL unit is extracted from the socket stream.
     * Arguments: (data: ByteArray, offset: Int, length: Int)
     */
    var onNalUnitReceived: ((ByteArray, Int, Int) -> Unit)? = null

    /**
     * Secondary callback alias for incoming data packets.
     */
    var onDataReceived: ((ByteArray, Int, Int) -> Unit)? = null

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        clientThread = thread(name = "StreamClientThread") {
            runClientLoop()
        }
        Log.i(TAG, "StreamClient started, connecting to $host:$port")
    }

    private fun runClientLoop() {
        while (isRunning) {
            var currentSocket: Socket? = null
            var wasConnected = false
            try {
                Log.i(TAG, "Attempting TCP connection to $host:$port")
                val s = Socket()
                s.tcpNoDelay = true
                s.receiveBufferSize = 256 * 1024
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket = s
                currentSocket = s
                wasConnected = true

                Log.i(TAG, "Successfully connected to $host:$port")
                onConnected?.invoke()

                val inputStream = s.getInputStream()
                readStream(inputStream)

            } catch (e: IOException) {
                if (isRunning) {
                    Log.w(TAG, "Socket connection error ($host:$port): ${e.message}")
                    onError?.invoke(e)
                }
            } finally {
                try {
                    currentSocket?.close()
                } catch (_: Exception) {}
                if (socket === currentSocket) {
                    socket = null
                }
                if (wasConnected) {
                    onDisconnected?.invoke()
                }
            }

            if (isRunning) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        Log.i(TAG, "StreamClient loop terminated.")
    }

    private fun readStream(inputStream: InputStream) {
        val readBuf = ByteArray(READ_BUFFER_SIZE)
        var accumulator = ByteArray(INITIAL_ACCUMULATOR_SIZE)
        var tail = 0

        while (isRunning) {
            val bytesRead = inputStream.read(readBuf)
            if (bytesRead == -1) {
                Log.i(TAG, "Socket reached EOF")
                break
            }
            if (bytesRead == 0) continue

            // Ensure capacity in accumulator
            if (tail + bytesRead > accumulator.size) {
                var newCap = accumulator.size * 2
                while (newCap < tail + bytesRead) {
                    newCap *= 2
                }
                accumulator = accumulator.copyOf(newCap)
            }

            System.arraycopy(readBuf, 0, accumulator, tail, bytesRead)
            tail += bytesRead

            // Process and dispatch all complete NAL units
            tail = processNalUnits(accumulator, tail)
        }

        // Flush any remaining NAL unit
        if (tail > 0) {
            val startPos = findStartCode(accumulator, 0, tail)
            if (startPos != -1 && startPos < tail) {
                emitNalUnit(accumulator, startPos, tail - startPos)
            }
        }
    }

    private fun processNalUnits(data: ByteArray, currentTail: Int): Int {
        var tail = currentTail
        while (tail > 0) {
            val startPos = findStartCode(data, 0, tail)
            if (startPos == -1) {
                if (tail > 4) {
                    // Retain the last 3 bytes in case a start code is split across socket reads
                    System.arraycopy(data, tail - 3, data, 0, 3)
                    tail = 3
                }
                break
            }

            if (startPos > 0) {
                // Discard any data before the first start code
                System.arraycopy(data, startPos, data, 0, tail - startPos)
                tail -= startPos
            }

            val prefixLen = maxOf(getStartCodeLength(data, 0, tail), 3)
            val nextStart = findStartCode(data, prefixLen, tail)
            if (nextStart != -1) {
                // Complete NAL unit found from index 0 until nextStart
                emitNalUnit(data, 0, nextStart)
                System.arraycopy(data, nextStart, data, 0, tail - nextStart)
                tail -= nextStart
            } else {
                // Waiting for next chunk to complete current NAL unit
                break
            }
        }
        return tail
    }

    private fun emitNalUnit(data: ByteArray, offset: Int, length: Int) {
        val nal = data.copyOfRange(offset, offset + length)
        onNalUnitReceived?.invoke(nal, 0, nal.size)
        onDataReceived?.invoke(nal, 0, nal.size)
    }

    private fun findStartCode(data: ByteArray, start: Int, end: Int): Int {
        var i = start
        while (i <= end - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    return i
                }
                if (i <= end - 4 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun getStartCodeLength(data: ByteArray, pos: Int, end: Int): Int {
        if (pos + 4 <= end && data[pos] == 0.toByte() && data[pos + 1] == 0.toByte() && data[pos + 2] == 0.toByte() && data[pos + 3] == 1.toByte()) {
            return 4
        }
        if (pos + 3 <= end && data[pos] == 0.toByte() && data[pos + 1] == 0.toByte() && data[pos + 2] == 1.toByte()) {
            return 3
        }
        return 0
    }

    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        clientThread?.interrupt()
        try {
            clientThread?.join(1000)
        } catch (_: InterruptedException) {}
        clientThread = null
        Log.i(TAG, "StreamClient stopped.")
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false
}
