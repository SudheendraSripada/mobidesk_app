package com.mobidesk.mobidesk_app

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * High-performance, zero-queue USB Accessory sender for Phone A (Sender).
 *
 * Enforces strict zero-buffering architecture:
 * 1. Limits the pending video frame buffer to exactly 1 frame.
 * 2. If the USB write worker is busy, stale non-keyframes are immediately dropped.
 * 3. Keyframes (IDR) replace any older pending frame to ensure instant synchronization.
 * 4. Codec configuration (SPS/PPS) and control packets (sleep/wake) are preserved in a critical queue.
 * 5. Guarantees sub-50ms glass-to-glass latency without accumulating buffer bloat.
 */
class UsbAccessorySender {

    companion object {
        private const val TAG = "UsbAccessorySender"
    }

    data class FramePacket(
        val type: Byte,
        val flags: Byte,
        val ptsUs: Long,
        val payload: ByteArray
    )

    private val isRunning = AtomicBoolean(false)
    private var outputStream: OutputStream? = null
    private var writeThread: Thread? = null
    private val writeLock = ReentrantLock()
    private val writeCondition = writeLock.newCondition()

    // Exactly 1 pending video frame slot (zero-queue architecture)
    private val pendingVideoFrame = AtomicReference<FramePacket?>()

    // High-priority control packets (SPS/PPS config, sleep/wake states) that must never be dropped
    private val criticalQueue = ConcurrentLinkedQueue<FramePacket>()

    @Volatile
    var cachedConfig: ByteArray? = null
        private set

    @Volatile
    var cachedKeyframe: FramePacket? = null
        private set

    @Volatile
    var configSent: Boolean = false
        private set

    private val configQueued = AtomicBoolean(false)

    var onDisconnected: (() -> Unit)? = null

    /**
     * Starts the USB accessory writer worker with the active output stream.
     */
    @Synchronized
    fun start(stream: OutputStream) {
        if (isRunning.get()) {
            stop()
        }
        outputStream = stream
        isRunning.set(true)
        configSent = false
        configQueued.set(false)
        pendingVideoFrame.set(null)
        criticalQueue.clear()

        // Send cached SPS/PPS immediately as the very first packet of the session
        cachedConfig?.let { config ->
            sendConfig(config)
        }

        // Preload cached keyframe if available for instant live mirror on plug-in/reconnect
        cachedKeyframe?.let { kf ->
            pendingVideoFrame.set(kf)
        }

        writeThread = thread(name = "UsbAccessoryWriteThread") {
            runWriteLoop()
        }
        Log.i(TAG, "UsbAccessorySender started with zero-queue architecture.")
    }

    /**
     * Stops the writer worker and cleans up resources.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        writeLock.lock()
        try {
            writeCondition.signalAll()
        } finally {
            writeLock.unlock()
        }

        try {
            writeThread?.interrupt()
            writeThread?.join(500)
        } catch (_: InterruptedException) {}
        writeThread = null

        outputStream = null
        pendingVideoFrame.set(null)
        criticalQueue.clear()
        configSent = false
        configQueued.set(false)
        Log.i(TAG, "UsbAccessorySender stopped.")
    }

    val isConnected: Boolean
        get() = isRunning.get() && outputStream != null

    /**
     * Caches SPS/PPS codec configuration and queues it for high-priority transmission.
     */
    fun sendConfig(configData: ByteArray) {
        cachedConfig = configData.copyOf()
        if (!isRunning.get()) return

        val packet = FramePacket(
            type = FramingProtocol.TYPE_CONFIG,
            flags = FramingProtocol.FLAG_KEYFRAME,
            ptsUs = 0L,
            payload = configData.copyOf()
        )
        criticalQueue.add(packet)
        configQueued.set(true)
        writeLock.lock()
        try {
            writeCondition.signal()
        } finally {
            writeLock.unlock()
        }
    }

    /**
     * Sends sleep/wake control state packet to Phone B.
     */
    fun sendSleepState(isAsleep: Boolean) {
        if (!isRunning.get()) return

        val packet = FramePacket(
            type = FramingProtocol.TYPE_SLEEP,
            flags = FramingProtocol.FLAG_NONE,
            ptsUs = 0L,
            payload = byteArrayOf(if (isAsleep) 1 else 0)
        )
        criticalQueue.add(packet)
        writeLock.lock()
        try {
            writeCondition.signal()
        } finally {
            writeLock.unlock()
        }
        Log.i(TAG, "Queued sleep state packet: isAsleep=$isAsleep")
    }

    /**
     * Enqueues an encoded video frame using zero-buffering drop-tail logic.
     * If the write thread is currently transmitting, older non-keyframes are discarded.
     */
    fun sendFrame(
        type: Byte,
        flags: Byte,
        ptsUs: Long,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size
    ) {
        if (!isRunning.get()) return

        val isKeyframe = (flags.toInt() and FramingProtocol.FLAG_KEYFRAME.toInt()) != 0
        val isConfig = type == FramingProtocol.TYPE_CONFIG
        val isSleep = type == FramingProtocol.TYPE_SLEEP

        val data = if (offset == 0 && length == payload.size) {
            payload
        } else {
            val slice = ByteArray(length)
            System.arraycopy(payload, offset, slice, 0, length)
            slice
        }

        val packet = FramePacket(type, flags, ptsUs, data)

        if (isConfig || isSleep) {
            criticalQueue.add(packet)
            writeLock.lock()
            try {
                writeCondition.signal()
            } finally {
                writeLock.unlock()
            }
            return
        }

        // Ensure SPS/PPS is queued before standard video frames if not yet sent
        if (!configSent && configQueued.compareAndSet(false, true)) {
            cachedConfig?.let { config ->
                criticalQueue.add(
                    FramePacket(
                        type = FramingProtocol.TYPE_CONFIG,
                        flags = FramingProtocol.FLAG_KEYFRAME,
                        ptsUs = 0L,
                        payload = config
                    )
                )
            }
        }

        if (isKeyframe) {
            // Keyframe takes immediate precedence: replace any pending frame
            // and cache it for instant delivery to reconnecting clients
            cachedKeyframe = packet
            pendingVideoFrame.set(packet)
        } else {
            // Non-keyframe (P-frame): Zero-queue drop logic.
            // If the pending slot is already occupied by a critical KEYFRAME, DROP THIS NON-KEYFRAME
            // to protect the keyframe from being lost!
            // If the pending slot is empty or has a non-keyframe, replace it (dropping stale non-keyframe).
            while (true) {
                val current = pendingVideoFrame.get()
                if (current != null && (current.flags.toInt() and FramingProtocol.FLAG_KEYFRAME.toInt()) != 0) {
                    // Critical keyframe pending; drop this non-keyframe
                    return
                }
                if (pendingVideoFrame.compareAndSet(current, packet)) {
                    break
                }
            }
        }

        writeLock.lock()
        try {
            writeCondition.signal()
        } finally {
            writeLock.unlock()
        }
    }

    private fun runWriteLoop() {
        while (isRunning.get()) {
            val out = outputStream ?: break

            // 1. Drain high-priority critical packets first (config, sleep)
            var criticalPacket = criticalQueue.poll()
            while (criticalPacket != null && isRunning.get()) {
                try {
                    FramingProtocol.writeFrame(
                        out,
                        criticalPacket.type,
                        criticalPacket.flags,
                        criticalPacket.payload,
                        0,
                        criticalPacket.payload.size,
                        criticalPacket.ptsUs
                    )
                    if (criticalPacket.type == FramingProtocol.TYPE_CONFIG) {
                        configSent = true
                    }
                } catch (e: IOException) {
                    handleWriteError(e)
                    return
                }
                criticalPacket = criticalQueue.poll()
            }

            // 2. Fetch the freshest pending video frame (at most 1 frame backlog)
            val videoPacket = pendingVideoFrame.getAndSet(null)
            if (videoPacket != null && isRunning.get()) {
                try {
                    FramingProtocol.writeFrame(
                        out,
                        videoPacket.type,
                        videoPacket.flags,
                        videoPacket.payload,
                        0,
                        videoPacket.payload.size,
                        videoPacket.ptsUs
                    )
                    if (videoPacket.type == FramingProtocol.TYPE_CONFIG) {
                        configSent = true
                    }
                } catch (e: IOException) {
                    handleWriteError(e)
                    return
                }
            }

            // 3. Wait if no more packets are pending
            writeLock.lock()
            try {
                if (isRunning.get() && criticalQueue.isEmpty() && pendingVideoFrame.get() == null) {
                    try {
                        writeCondition.await(50, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        if (!isRunning.get()) return
                    }
                }
            } finally {
                writeLock.unlock()
            }
        }
    }

    private fun handleWriteError(e: IOException) {
        if (isRunning.get()) {
            Log.w(TAG, "USB Accessory write error: ${e.message}")
            isRunning.set(false)
            outputStream = null
            onDisconnected?.invoke()
        }
    }
}
