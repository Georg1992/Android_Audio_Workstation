package com.georgv.audioworkstation.ui.playback

import com.georgv.audioworkstation.core.audio.WavPcmFileInfo
import java.io.InputStream
import java.util.concurrent.CountDownLatch

internal class MixSnap(
    val url: String,
    val info: WavPcmFileInfo,
    val pieceBytes: Int,
    val pcmByte: Long,
    val playIndex: Int,
    val generation: Int,
    val playing: Boolean,
    val holding: Boolean,
)

internal class MixStart(val latch: CountDownLatch = CountDownLatch(1)) {
    var failure: Exception? = null
}

internal class ReleasedStream(val stream: InputStream?)

internal class MixPlayState(internal val buffer: MixPieceBuffer) {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    internal val lock = Object()
    internal var url: String? = null
    internal var header: WavPcmFileInfo? = null
    internal var pieceBytes: Int = 0
    internal var pcmByte: Long = 0
    internal var playing = false
    internal var closed = true
    internal var broken = false
    internal var generation = 0
    internal var holding = false
    internal var held: HeldPiece? = null
    internal var inflight: InputStream? = null
    internal var start: MixStart? = null
    internal var stall: Exception? = null
    internal var stallSent = false

    fun open(url: String): CountDownLatch {
        synchronized(lock) {
            this.url = url
            header = null
            pieceBytes = 0
            pcmByte = 0
            playing = true
            closed = false
            broken = false
            holding = false
            held = null
            generation += 1
            stall = null
            stallSent = false
            val gate = MixStart()
            start = gate
            return gate.latch
        }
    }

    fun resume(url: String): ReleasedStream? {
        synchronized(lock) {
            val ready = header != null && !closed && !broken
            val same = this.url == url && ready
            if (!same) return null
            playing = true
            pcmByte = 0
            holding = false
            held = null
            generation += 1
            val open = inflight
            inflight = null
            lock.notifyAll()
            return ReleasedStream(open)
        }
    }

    fun retarget(pcm: Long, audible: Boolean): InputStream? {
        synchronized(lock) {
            if (closed) return null
            pcmByte = pcm
            playing = audible
            holding = false
            held = null
            generation += 1
            val open = inflight
            inflight = null
            lock.notifyAll()
            return open
        }
    }

    fun closeSession(): InputStream? {
        synchronized(lock) {
            closed = true
            playing = false
            broken = true
            val open = inflight
            inflight = null
            start?.latch?.countDown()
            lock.notifyAll()
            return open
        }
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

    fun snapshot(): MixSnap? {
        synchronized(lock) {
            val parsed = header ?: return null
            val address = url ?: return null
            if (closed || broken) return null
            val bytes = pieceBytes
            return MixSnap(
                url = address,
                info = parsed,
                pieceBytes = bytes,
                pcmByte = pcmByte,
                playIndex = MixPieces.indexAt(pcmByte, bytes),
                generation = generation,
                playing = playing,
                holding = holding,
            )
        }
    }

    fun acceptHeader(info: WavPcmFileInfo): Boolean {
        synchronized(lock) {
            if (closed) return false
            header = info
            pieceBytes = MixPieces.pieceBytes(info.sampleRateHz, info.blockAlign)
            return true
        }
    }

    fun advance(count: Int, pieceGeneration: Int): Boolean {
        synchronized(lock) {
            val current = !closed && playing && generation == pieceGeneration
            if (!current) return false
            pcmByte += count
            return true
        }
    }

    fun hold(piece: HeldPiece?): HeldPiece? {
        synchronized(lock) {
            val previous = held
            held = piece
            holding = piece != null
            val gate = start
            if (piece != null && gate != null) {
                gate.failure = null
                gate.latch.countDown()
                start = null
            }
            return previous
        }
    }

    fun takeHeld(): HeldPiece? {
        synchronized(lock) {
            val piece = held
            held = null
            return piece
        }
    }

    fun attach(stream: InputStream): Boolean {
        synchronized(lock) {
            if (closed) return false
            inflight = stream
            return true
        }
    }

    fun detach(stream: InputStream) {
        synchronized(lock) {
            if (inflight === stream) inflight = null
        }
    }

    fun pulse(): Boolean {
        synchronized(lock) {
            if (closed) return false
            try {
                lock.wait(PollMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            return !closed
        }
    }

    fun address(): String? = synchronized(lock) { if (closed) null else url }

    private companion object {
        const val MsPerSecond = 1_000L
        const val PollMs = 10L
    }
}
