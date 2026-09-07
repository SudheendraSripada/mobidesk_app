package com.mobidesk.mobidesk_app

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * TCP Server that listens on port 8888 and streams raw H.264 video packets
 * continuously to connected clients (e.g. Phone B over USB tethering).
 */
class StreamServer(val port: Int = DEFAULT_PORT) {

    companion object {
        private const val TAG = "StreamServer"
        const val DEFAULT_PORT = 8888
    }

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private var acceptThread: Thread? = null

    private val connectedClients = CopyOnWriteArrayList<Socket>()
    @Volatile
    private var latestConfigHeader: ByteArray? = null

    /**
     * Callback invoked when a client successfully connects and receives the initial config header,
     * allowing ScreenCaptureService to immediately request a sync frame (IDR/keyframe).
     */
    var onClientConnected: (() -> Unit)? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        try {
            // Properly set SO_REUSEADDR before binding to prevent Address In Use errors on quick restart
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            Log.i(TAG, "StreamServer listening on port $port")

            acceptThread = thread(name = "StreamServerAcceptThread") {
                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        socket.tcpNoDelay = true
                        socket.sendBufferSize = 256 * 1024
                        Log.i(TAG, "Client connected: ${socket.remoteSocketAddress}")

                        var sentConfigOk = true
                        // Send SPS/PPS header to newly connected client immediately
                        latestConfigHeader?.let { config ->
                            try {
                                val out = socket.getOutputStream()
                                out.write(config)
                                out.flush()
                            } catch (e: IOException) {
                                Log.w(TAG, "Failed to send initial SPS/PPS to client: ${e.message}")
                                sentConfigOk = false
                                try { socket.close() } catch (_: Exception) {}
                            }
                        }

                        if (sentConfigOk && !socket.isClosed) {
                            connectedClients.add(socket)
                            Log.i(TAG, "Client registered successfully: ${socket.remoteSocketAddress}")
                            onClientConnected?.invoke()
                        }
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "Accept error: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not start server on port $port: ${e.message}", e)
            isRunning = false
        }
    }

    /**
     * Cache and forward the H.264 SPS/PPS (codec config) header data.
     */
    fun sendConfig(configData: ByteArray) {
        latestConfigHeader = configData.copyOf()
        sendPacket(configData)
    }

    /**
     * Send raw H.264 data packets continuously to all connected clients.
     */
    fun sendPacket(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (!isRunning || connectedClients.isEmpty()) return

        for (client in connectedClients) {
            try {
                val out: OutputStream = client.getOutputStream()
                out.write(data, offset, length)
                out.flush()
            } catch (e: IOException) {
                Log.w(TAG, "Error writing to client ${client.remoteSocketAddress}: ${e.message}")
                try {
                    client.close()
                } catch (_: Exception) {}
                connectedClients.remove(client)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null

        for (client in connectedClients) {
            try {
                client.close()
            } catch (_: Exception) {}
        }
        connectedClients.clear()

        acceptThread?.interrupt()
        acceptThread = null
        latestConfigHeader = null
        Log.i(TAG, "StreamServer stopped")
    }

    fun hasConnectedClients(): Boolean = connectedClients.isNotEmpty()
}

/**
 * Backwards compatibility alias for any legacy references to TcpStreamServer.
 */
typealias TcpStreamServer = StreamServer

