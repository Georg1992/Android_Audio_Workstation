package com.georgv.audioworkstation.ui.playback

import com.georgv.audioworkstation.core.audio.WavPcmFileInfo
import com.georgv.audioworkstation.core.audio.WavStreamHeader
import java.io.ByteArrayInputStream
import java.io.IOException

internal class MixSession(
    private val state: MixPlayState,
    private val reader: MixPieceReader,
    private val buffer: MixPieceBuffer,
    private val output: PcmOutput,
    private val aheadPieces: Int,
) {
    private var outputLive = false
    private var fetcher: Thread? = null

    fun run() {
        try {
            val info = readHeader() ?: return
            if (!state.acceptHeader(info)) return
            output.start(info.sampleRateHz, info.channelCount)
            if (!prime(info)) return
            startFetcher()
            consume()
        } catch (failure: Exception) {
            state.failHard(failure)
        } finally {
            joinFetcher()
        }
    }

    private fun readHeader(): WavPcmFileInfo? {
        var parsed: WavPcmFileInfo? = null
        while (parsed == null) {
            val url = state.address() ?: return null
            parsed = readHeaderOnce(url)
        }
        return parsed
    }

    private fun readHeaderOnce(url: String): WavPcmFileInfo? {
        try {
            val bytes = reader.read(url, 0L, MixPieces.HeaderEnd, exact = false) ?: return null
            val parsed = WavStreamHeader.read(ByteArrayInputStream(bytes))
            if (parsed == null || !parsed.isSupportedProjectPcm || parsed.dataSize <= 0L) {
                error("mix is not pcm")
            }
            state.noteReady()
            return parsed
        } catch (io: IOException) {
            state.noteStall(io)
            sleep(RetryMs)
            return null
        }
    }

    private fun prime(info: WavPcmFileInfo): Boolean {
        while (true) {
            val url = state.address() ?: return false
            val snap = state.snapshot() ?: return false
            val span = MixPieces.span(info, snap.pieceBytes, snap.playIndex) ?: error("mix is not pcm")
            try {
                val bytes = reader.read(url, span.fileStart, span.fileEndInclusive, exact = true) ?: continue
                val piece = HeldPiece(span.index, span.pcmStart, bytes, snap.generation, ended = false)
                state.noteReady()
                arm(true)
                state.hold(piece)
                return true
            } catch (bad: IllegalStateException) {
                state.failHard(bad)
                return false
            } catch (io: IOException) {
                if (state.snapshot()?.generation != snap.generation) continue
                state.noteStall(io)
                if (!sleep(RetryMs)) return false
            }
        }
    }

    private fun consume() {
        var piece = state.takeHeld()
        while (true) {
            val snap = state.snapshot() ?: return
            if (!snap.playing) {
                arm(false)
                if (!state.pulse()) return
                continue
            }
            val current = aligned(piece, snap.generation) ?: return
            piece = current
            writePiece(current)
            piece = null
            state.hold(null)
        }
    }

    private fun aligned(piece: HeldPiece?, generation: Int): HeldPiece? {
        if (piece != null && piece.generation == generation) return piece
        arm(false)
        state.hold(null)
        return nextPiece()
    }

    private fun nextPiece(): HeldPiece? {
        while (true) {
            if (state.address() == null) return null
            val taken = buffer.take() ?: return null
            val snap = state.snapshot() ?: return null
            if (taken.generation != snap.generation) continue
            if (!taken.ended) {
                state.hold(taken)
                state.noteReady()
                return taken
            }
            state.finish()
        }
    }

    private fun writePiece(piece: HeldPiece): Boolean {
        val snapStart = state.snapshot() ?: return false
        val relative = (snapStart.pcmByte - piece.pcmStart).toInt()
        var offset = relative.coerceIn(0, piece.bytes.size)
        if (offset < piece.bytes.size) arm(true)
        while (offset < piece.bytes.size) {
            val snap = state.snapshot() ?: return false
            val stop = !snap.playing || snap.generation != piece.generation
            if (stop) return false
            val length = minOf(ChunkBytes, piece.bytes.size - offset)
            val wrote = output.write(piece.bytes, offset, length)
            if (wrote <= 0) return false
            if (!state.advance(wrote, piece.generation)) return false
            offset += wrote
        }
        return true
    }

    private fun arm(active: Boolean) {
        if (active && !outputLive) {
            output.play()
            outputLive = true
        }
        if (!active && outputLive) {
            output.pause()
            outputLive = false
        }
    }

    private fun sleep(delayMs: Long): Boolean {
        try {
            Thread.sleep(delayMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return state.address() != null
    }

    private fun startFetcher() {
        val thread = Thread({
            MixPieceFetcher(reader, buffer, state, aheadPieces).run()
        }, FetchName)
        thread.isDaemon = true
        fetcher = thread
        thread.start()
    }

    private fun joinFetcher() {
        val thread = fetcher
        fetcher = null
        if (thread != null && thread != Thread.currentThread()) thread.join(JoinMs)
    }

    private companion object {
        const val FetchName = "shared-mix-fetch"
        const val ChunkBytes = 4096
        const val RetryMs = 200L
        const val JoinMs = 1_000L
    }
}
