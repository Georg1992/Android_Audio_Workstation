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
                if (state.snapshot()?.generation != snap.generation) continue
                val piece = HeldPiece(span.index, span.pcmStart, bytes, snap.generation, ended = false)
                if (!buffer.store(piece)) return false
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
            val previous = piece?.generation
            val current = readyPiece(piece, snap)
            if (current == null) {
                if (state.address() == null || Thread.currentThread().isInterrupted) return
                continue
            }
            if (previous != null && current.generation != previous) arm(false)
            piece = current
            if (writePiece(current)) {
                piece = null
                state.hold(null)
            }
        }
    }

    private fun readyPiece(piece: HeldPiece?, snap: MixSnap): HeldPiece? {
        val kept = reused(piece, snap)
        if (kept != null) return kept
        if (piece != null) arm(false)
        state.hold(null)
        val live = state.snapshot() ?: return null
        if (!live.playing || live.generation != snap.generation) return null
        val stored = buffer.awaitPiece(live.playIndex) ?: return null
        val now = state.snapshot() ?: return null
        val moved = !now.playing || now.generation != live.generation || now.playIndex != live.playIndex
        if (moved) return null
        if (stored.ended) {
            state.finish()
            return null
        }
        val tagged = stored.retag(now.generation)
        state.hold(tagged)
        state.noteReady()
        return tagged
    }

    private fun reused(piece: HeldPiece?, snap: MixSnap): HeldPiece? {
        if (piece == null || !piece.covers(snap.pcmByte)) return null
        val tagged = if (piece.generation == snap.generation) piece else piece.retag(snap.generation)
        state.hold(tagged)
        return tagged
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
            val length = minOf(MixPieces.PlayChunk, piece.bytes.size - offset)
            val wrote = output.write(piece.bytes, offset, length)
            if (wrote <= 0) return true
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
        const val RetryMs = 200L
        const val JoinMs = 1_000L
    }
}
