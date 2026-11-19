package com.georgv.audioworkstation.ui.playback

import com.georgv.audioworkstation.core.audio.convert.AacMixFile
import java.io.InputStream

/**
 * One download of the AAC file. Piece reads wait until those bytes have arrived,
 * so the playhead can stay put until that moment is actually on the phone.
 */
internal class AacByteCache(private val remote: MixSource) {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private var active: SongLoad? = null

    fun read(url: String, start: Long, endInclusive: Long): ByteArray {
        val load = begin(url)
        val bytes = load.await(endInclusive)
        return bytes.copyOfRange(start.toInt(), (endInclusive + 1L).toInt())
    }

    private fun begin(url: String): SongLoad {
        synchronized(lock) {
            val current = active
            if (current != null && current.url == url && current.failure == null) return current
            current?.cancel()
            val load = SongLoad(url)
            active = load
            val thread = Thread({ load.run(remote) }, ThreadName)
            thread.isDaemon = true
            load.thread = thread
            thread.start()
            return load
        }
    }

    private companion object {
        const val ThreadName = "aac-mix-cache"
    }
}

internal class SongLoad(val url: String) {
    var thread: Thread? = null
    var failure: Exception? = null

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private var data = ByteArray(0)
    private var filled = 0
    private var stream: InputStream? = null
    private var finished = false

    fun run(remote: MixSource) {
        try {
            val index = AacMixFile.read { start, end -> remoteBytes(remote, start, end) }
            val total = index.fileSize
            if (total <= 0L || total > Int.MAX_VALUE) error("mix is not aac")
            val bytes = ByteArray(total.toInt())
            val input = remote.open(url, 0L, total - 1L)
            synchronized(lock) { stream = input }
            input.use { fill(it, bytes) }
        } catch (io: java.io.IOException) {
            fail(io)
        } catch (bad: IllegalStateException) {
            fail(bad)
        }
    }

    fun await(endInclusive: Long): ByteArray {
        synchronized(lock) {
            while (filled <= endInclusive && failure == null && !finished) {
                try {
                    lock.wait()
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    error("mix is not aac")
                }
            }
            val error = failure
            if (error != null) throw error
            if (filled <= endInclusive) error("mix is not aac")
            return data
        }
    }

    fun cancel() {
        thread?.interrupt()
        synchronized(lock) {
            stream?.close()
            if (failure == null) failure = IllegalStateException("mix is not aac")
            finished = true
            lock.notifyAll()
        }
    }

    private fun fill(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) error("mix is not aac")
            offset += count
            synchronized(lock) {
                data = bytes
                filled = offset
                if (offset == bytes.size) finished = true
                lock.notifyAll()
            }
        }
    }

    private fun remoteBytes(remote: MixSource, start: Long, endInclusive: Long): ByteArray {
        val size = (endInclusive - start + 1L).toInt()
        val out = ByteArray(size)
        remote.open(url, start, endInclusive).use { input ->
            var offset = 0
            while (offset < size) {
                val count = input.read(out, offset, size - offset)
                if (count < 0) error("mix is not aac")
                offset += count
            }
        }
        return out
    }

    private fun fail(error: Exception) {
        synchronized(lock) {
            if (failure == null) failure = error
            finished = true
            lock.notifyAll()
        }
    }
}
