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

/**
 * Stored pieces keyed by index.
 * Only the piece at the playhead and a short stretch after it are kept.
 */
internal class MixPieceBuffer(private val limit: Int) {
    private val pieces = HashMap<Int, HeldPiece>()
    private var closed = false

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()

    fun store(piece: HeldPiece): Boolean {
        synchronized(lock) {
            if (closed) return false
            pieces[piece.index] = piece
            lock.notifyAll()
            return true
        }
    }

    fun piece(index: Int): HeldPiece? = synchronized(lock) { pieces[index] }

    fun retain(fromIndex: Int) {
        synchronized(lock) {
            val last = fromIndex + limit - 1
            val keys = pieces.keys.iterator()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key < fromIndex || key > last) keys.remove()
            }
            lock.notifyAll()
        }
    }

    fun kick() {
        synchronized(lock) {
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
            pieces.clear()
            closed = false
            lock.notifyAll()
        }
    }

    fun size(): Int = synchronized(lock) { pieces.size }

    fun awaitPiece(index: Int): HeldPiece? {
        synchronized(lock) {
            val ready = pieces[index]
            if (ready != null) return ready
            if (closed) return null
            try {
                lock.wait()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            if (closed) return null
            return pieces[index]
        }
    }
}
