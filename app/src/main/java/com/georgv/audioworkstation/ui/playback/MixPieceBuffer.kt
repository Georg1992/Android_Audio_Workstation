package com.georgv.audioworkstation.ui.playback

internal class HeldPiece(
    val index: Int,
    val pcmStart: Long,
    val bytes: ByteArray,
    val generation: Int,
    val ended: Boolean,
) {
    fun covers(pcm: Long): Boolean = !ended && pcm >= pcmStart && pcm < pcmStart + bytes.size

    fun retag(generation: Int): HeldPiece =
        if (generation == this.generation) this else HeldPiece(index, pcmStart, bytes, generation, ended)
}

/** Upcoming pieces only. A full buffer stops the fetcher from reading the rest of the mix. */
internal class MixPieceBuffer(private val limit: Int) {
    private val queue = ArrayDeque<HeldPiece>()
    private var queued = 0
    private var byteCap = 0
    private var closed = false

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()

    fun limitBytes(pieceBytes: Int) {
        synchronized(lock) {
            byteCap = if (pieceBytes > 0) limit * pieceBytes else 0
            lock.notifyAll()
        }
    }

    fun put(piece: HeldPiece): Boolean {
        synchronized(lock) {
            while (exceeds(piece) && !closed) {
                if (!waitForRoom()) return false
            }
            if (closed) return false
            queue.addLast(piece)
            queued += piece.bytes.size
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
            queued = (queued - piece.bytes.size).coerceAtLeast(0)
            lock.notifyAll()
            return piece
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
            queued = 0
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
            queued = 0
            byteCap = 0
            closed = false
            lock.notifyAll()
        }
    }

    fun size(): Int = synchronized(lock) { queue.size }

    fun queuedBytes(): Int = synchronized(lock) { queued }

    private fun exceeds(incoming: HeldPiece): Boolean {
        val cap = byteCap
        if (cap <= 0) return queue.size >= limit
        if (queue.isEmpty()) return false
        return queued + incoming.bytes.size > cap
    }

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
