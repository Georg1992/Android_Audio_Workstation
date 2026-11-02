package com.georgv.audioworkstation.ui.playback

import com.georgv.audioworkstation.core.audio.WavPcmFileInfo
import com.georgv.audioworkstation.core.audio.WavStreamHeader
import com.georgv.audioworkstation.core.util.logWarning
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays a shared 16-bit PCM mix from its URL. [play] returns once the WAV header
 * is read and the output has started, then a thread keeps reading the body.
 * Stop closes that stream. Scrub reopens it with a range request.
 */
internal class SharedMixStream(
    private val source: MixSource = UrlMixSource(),
    private val output: PcmOutput = AudioTrackPcmOutput(),
) {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private var url: String? = null
    private var header: WavPcmFileInfo? = null
    private var stream: InputStream? = null
    private var pcmByte: Long = 0
    private var playing = false
    private var closed = true
    private var pendingSeek: Long? = null
    private var writer: Thread? = null
    private var startGate: CountDownLatch? = null
    private var startFailure: Exception? = null
    private var generation = 0
    private var outputRunning = false

    fun play(url: String) {
        val resume = synchronized(lock) {
            this.url == url && header != null && !closed && writer?.isAlive == true
        }
        if (resume) {
            synchronized(lock) {
                playing = true
                if (stream == null && pendingSeek == null) {
                    val parsed = header
                    if (parsed != null) pendingSeek = parsed.dataOffset + pcmByte
                }
                lock.notifyAll()
            }
            return
        }
        begin(url)
    }

    fun stop() {
        val open = synchronized(lock) {
            playing = false
            pcmByte = 0
            pendingSeek = null
            val open = stream
            stream = null
            lock.notifyAll()
            open
        }
        open?.close()
    }

    fun seekTo(positionMs: Int) {
        val parsed = synchronized(lock) { header } ?: return
        if (parsed.sampleRateHz <= 0 || parsed.blockAlign <= 0) return
        val duration = parsed.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val clamped = positionMs.coerceIn(0, duration)
        val frames = clamped.toLong() * parsed.sampleRateHz / MsPerSecond
        val frameCount = parsed.dataSize / parsed.blockAlign
        val pcm = frames.coerceAtMost(frameCount) * parsed.blockAlign
        val open = synchronized(lock) {
            if (closed) return
            pendingSeek = parsed.dataOffset + pcm
            pcmByte = pcm
            val open = stream
            stream = null
            lock.notifyAll()
            open
        }
        open?.close()
    }

    fun transport(): StreamTransport {
        synchronized(lock) {
            val parsed = header
            if (parsed == null || parsed.sampleRateHz <= 0 || parsed.blockAlign <= 0) {
                return StreamTransport(playing = playing && !closed)
            }
            val frames = pcmByte.coerceAtLeast(0) / parsed.blockAlign
            val position = ((frames * MsPerSecond) / parsed.sampleRateHz).toInt()
            val duration = parsed.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return StreamTransport(
                playing = playing && !closed,
                positionMs = position,
                durationMs = duration,
            )
        }
    }

    fun release() {
        stopWriter()
        output.release()
        synchronized(lock) {
            header = null
            url = null
            pcmByte = 0
            outputRunning = false
        }
    }

    private fun begin(url: String) {
        stopWriter()
        val gate = CountDownLatch(1)
        synchronized(lock) {
            this.url = url
            header = null
            stream = null
            pcmByte = 0
            pendingSeek = null
            playing = true
            closed = false
            startFailure = null
            startGate = gate
            outputRunning = false
            generation += 1
            val serial = generation
            writer = Thread({ runWriter(serial) }, ThreadName).also { thread ->
                thread.isDaemon = true
                thread.start()
            }
        }
        val started = try {
            gate.await(StartWaitMs, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!started) {
            stopWriter()
            error("mix stream did not start")
        }
        val failure = synchronized(lock) { startFailure }
        if (failure != null) throw failure
    }

    private fun stopWriter() {
        val (thread, open) = synchronized(lock) {
            closed = true
            playing = false
            pendingSeek = null
            val open = stream
            stream = null
            lock.notifyAll()
            val thread = writer
            writer = null
            thread to open
        }
        open?.close()
        if (thread != null && thread != Thread.currentThread()) thread.join(JoinMs)
    }

    private fun runWriter(serial: Int) {
        var open: InputStream? = null
        try {
            val address = synchronized(lock) { url } ?: return
            open = source.open(address, 0L)
            val parsed = WavStreamHeader.read(open) ?: error("mix is not pcm")
            if (!parsed.isSupportedProjectPcm || parsed.dataSize <= 0L) error("mix is not pcm")
            output.start(parsed.sampleRateHz, parsed.channelCount)
            val abandoned = synchronized(lock) {
                if (closed || serial != generation) {
                    true
                } else {
                    header = parsed
                    stream = open
                    open = null
                    false
                }
            }
            if (abandoned) {
                open?.close()
                synchronized(lock) {
                    startGate?.countDown()
                    startGate = null
                }
                return
            }
            if (synchronized(lock) { playing }) {
                output.play()
                outputRunning = true
            }
            synchronized(lock) {
                startGate?.countDown()
                startGate = null
            }
            loop(parsed, serial)
        } catch (failure: Exception) {
            val dangling = synchronized(lock) {
                val extra = open
                open = null
                val current = stream
                stream = null
                extra ?: current
            }
            dangling?.close()
            synchronized(lock) {
                playing = false
                if (startGate != null) {
                    startFailure = failure
                    startGate?.countDown()
                    startGate = null
                }
            }
        }
    }

    private fun loop(parsed: WavPcmFileInfo, serial: Int) {
        val buffer = ByteArray(ChunkBytes)
        while (pump(parsed, buffer, serial)) Unit
    }

    private fun pump(parsed: WavPcmFileInfo, buffer: ByteArray, serial: Int): Boolean {
        val step = synchronized(lock) { stepLocked(serial) } ?: return false
        if (step.reopen) {
            reopen(parsed, step.offset, serial)
            return true
        }
        if (!step.read) {
            armOutput(false)
            return true
        }
        armOutput(true)
        val current = synchronized(lock) { stream } ?: return true
        val count = readCount(current, buffer) ?: return true
        if (count < 0) {
            synchronized(lock) {
                if (serial == generation && pendingSeek == null) {
                    playing = false
                    stream = null
                }
            }
            return true
        }
        writeBytes(buffer, count, current, serial)
        return true
    }

    private fun armOutput(reading: Boolean) {
        if (reading && !outputRunning) {
            output.play()
            outputRunning = true
        }
        if (!reading && outputRunning) {
            output.pause()
            outputRunning = false
        }
    }

    private fun stepLocked(serial: Int): Step? {
        if (closed || serial != generation) return null
        val pending = pendingSeek
        if (pending != null && playing) {
            pendingSeek = null
            return Step(reopen = true, read = false, offset = pending)
        }
        if (!playing || stream == null) {
            try {
                lock.wait(PollMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                closed = true
                return null
            }
            return Step(reopen = false, read = false, offset = 0)
        }
        return Step(reopen = false, read = true, offset = 0)
    }

    private fun readCount(current: InputStream, buffer: ByteArray): Int? =
        try {
            current.read(buffer)
        } catch (interrupted: IOException) {
            val unexpected = synchronized(lock) { playing && pendingSeek == null && !closed }
            if (unexpected) logWarning(Tag, "mix read interrupted", interrupted)
            null
        }

    private fun writeBytes(buffer: ByteArray, count: Int, current: InputStream, serial: Int) {
        var offset = 0
        while (offset < count) {
            val written = output.write(buffer, offset, count - offset)
            if (written <= 0) return
            offset += written
            synchronized(lock) {
                val advancing = serial == generation && playing && pendingSeek == null
                if (advancing && stream === current) pcmByte += written
            }
        }
    }

    private fun reopen(parsed: WavPcmFileInfo, fileOffset: Long, serial: Int) {
        val previous = synchronized(lock) {
            val open = stream
            stream = null
            open
        }
        previous?.close()
        val address = synchronized(lock) {
            if (closed || serial != generation) null else url
        } ?: return
        val next = try {
            source.open(address, fileOffset)
        } catch (failure: IOException) {
            logWarning(Tag, "mix seek failed", failure)
            synchronized(lock) { playing = false }
            return
        }
        val keep = synchronized(lock) {
            val current = !closed && serial == generation && pendingSeek == null
            if (current) {
                stream = next
                pcmByte = (fileOffset - parsed.dataOffset).coerceAtLeast(0L)
            }
            current
        }
        if (!keep) {
            next.close()
            return
        }
        output.pause()
        outputRunning = false
        if (synchronized(lock) { playing }) {
            output.play()
            outputRunning = true
        }
    }

    private class Step(val reopen: Boolean, val read: Boolean, val offset: Long)

    private companion object {
        const val Tag = "SharedMixStream"
        const val ThreadName = "shared-mix"
        const val ChunkBytes = 4096
        const val StartWaitMs = 5_000L
        const val JoinMs = 1_000L
        const val PollMs = 10L
        const val MsPerSecond = 1_000L
    }
}

data class StreamTransport(
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)
