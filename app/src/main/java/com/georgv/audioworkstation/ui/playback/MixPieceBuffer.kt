package com.georgv.audioworkstation.ui.playback

internal class HeldPiece(
    val index: Int,
    val pcmStart: Long,
    val bytes: ByteArray,
    val generation: Int,
    val ended: Boolean,
)

/** Upcoming pieces only. A full buffer stops the fetcher from reading the rest of the mix. */
internal class MixPieceBuffer(private val limit: Int) {
    private val queue = ArrayDeque<HeldPiece>()
    private var closed = false

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()

    fun put(piece: HeldPiece): Boolean {
        synchronized(lock) {
            while (queue.size >= limit && !closed) {
                if (!waitForRoom()) return false
            }
            if (closed) return false
            queue.addLast(piece)
            lock.notifyAll()
            return true
        }
    }

    fun take(): HeldPiece? {
        synchronized(lock) {
            while (queue.isEmpty() && !closed) {
                if (!waitForRoom()) return null
            }
            if (queue.isEmpty()) return null
            val piece = queue.removeFirst()
            lock.notifyAll()
            return piece
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
            lock.notifyAll()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    fun reset() {
        synchronized(lock) {
            queue.clear()
            closed = false
            lock.notifyAll()
        }
    }

    fun size(): Int = synchronized(lock) { queue.size }

    private fun waitForRoom(): Boolean {
        try {
            lock.wait()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return true
    }
}
