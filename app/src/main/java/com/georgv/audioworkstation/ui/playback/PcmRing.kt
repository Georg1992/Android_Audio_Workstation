package com.georgv.audioworkstation.ui.playback

/**
 * Fixed window of PCM. Writers block when it is full, so a mix is never stored whole.
 */
internal class PcmRing(private val capacity: Int) {
    private val data = ByteArray(capacity)
    private var head = 0
    private var size = 0
    private var ended = false
    private var closed = false
    private var endDelivered = false
    private var discard = 0

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()

    fun put(src: ByteArray, offset: Int, length: Int): Int {
        if (length < 0) {
            synchronized(lock) {
                ended = true
                lock.notifyAll()
            }
            return 0
        }
        synchronized(lock) {
            val seen = discard
            while (size == capacity && !closed && discard == seen) {
                if (!waitForSpace()) return Closed
            }
            if (closed) return Closed
            if (discard != seen) return 0
            val count = minOf(length, capacity - size)
            copyIn(src, offset, count)
            size += count
            lock.notifyAll()
            return count
        }
    }

    fun take(dst: ByteArray, minReady: Int): Int {
        synchronized(lock) {
            val seen = discard
            val need = minReady.coerceAtLeast(1)
            var result = Ended
            while (true) {
                if (discard != seen) {
                    result = Discarded
                    break
                }
                if (closed) {
                    result = Closed
                    break
                }
                val ready = size >= need || (ended && size > 0)
                if (ready) {
                    result = copyOut(dst)
                    break
                }
                val finished = ended && size == 0
                if (finished && !endDelivered) {
                    endDelivered = true
                    break
                }
                if (!waitForSpace()) {
                    result = Closed
                    break
                }
            }
            return result
        }
    }

    fun clear() {
        synchronized(lock) {
            head = 0
            size = 0
            ended = false
            endDelivered = false
            discard += 1
            lock.notifyAll()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    private fun waitForSpace(): Boolean {
        try {
            lock.wait()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return true
    }

    private fun copyIn(src: ByteArray, offset: Int, count: Int) {
        val tail = (head + size) % capacity
        val first = minOf(count, capacity - tail)
        src.copyInto(data, tail, offset, offset + first)
        if (count > first) src.copyInto(data, 0, offset + first, offset + count)
    }

    private fun copyOut(dst: ByteArray): Int {
        val count = minOf(dst.size, size)
        val first = minOf(count, capacity - head)
        data.copyInto(dst, 0, head, head + first)
        if (count > first) data.copyInto(dst, first, 0, count - first)
        head = (head + count) % capacity
        size -= count
        lock.notifyAll()
        return count
    }

    companion object {
        const val Closed = -1
        const val Discarded = -2
        const val Ended = 0
    }
}
