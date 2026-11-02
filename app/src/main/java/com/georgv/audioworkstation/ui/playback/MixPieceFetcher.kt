package com.georgv.audioworkstation.ui.playback

import java.io.IOException

internal class PieceLoad(val bytes: ByteArray?, val keepGoing: Boolean)

internal class FetchCursor(
    var next: Int = 0,
    var seen: Int = -1,
    var postedEnd: Boolean = false,
)

internal class MixPieceFetcher(
    private val reader: MixPieceReader,
    private val buffer: MixPieceBuffer,
    private val state: MixPlayState,
    private val ahead: Int,
) {
    fun run() {
        val cursor = FetchCursor()
        while (step(cursor)) Unit
    }

    private fun step(cursor: FetchCursor): Boolean {
        val snap = state.snapshot() ?: return false
        if (!snap.playing) return sleep(PollMs)
        if (snap.generation != cursor.seen) {
            cursor.seen = snap.generation
            cursor.next = if (snap.holding) snap.playIndex + 1 else snap.playIndex
            cursor.postedEnd = false
        }
        if (cursor.next < snap.playIndex) cursor.next = snap.playIndex
        val queued = buffer.size()
        val upcoming = if (snap.holding) queued else (queued - 1).coerceAtLeast(0)
        if (upcoming >= ahead) return sleep(PollMs)
        return fetch(cursor, snap)
    }

    private fun fetch(cursor: FetchCursor, snap: MixSnap): Boolean {
        val span = MixPieces.span(snap.info, snap.pieceBytes, cursor.next) ?: return postEnd(cursor, snap)
        val loaded = load(snap, span, cursor.seen)
        val bytes = loaded.bytes
        if (bytes == null) return loaded.keepGoing
        val piece = HeldPiece(span.index, span.pcmStart, bytes, cursor.seen, ended = false)
        if (!buffer.put(piece)) return false
        if (state.snapshot()?.generation != cursor.seen) return true
        cursor.next += 1
        state.noteReady()
        return true
    }

    private fun load(snap: MixSnap, span: MixSpan, seen: Int): PieceLoad {
        try {
            val bytes = reader.read(snap.url, span.fileStart, span.fileEndInclusive, exact = true)
            val same = state.snapshot()?.generation == seen
            if (bytes == null || !same) return PieceLoad(null, keepGoing = true)
            return PieceLoad(bytes, keepGoing = true)
        } catch (io: IOException) {
            if (state.snapshot()?.generation != seen) return PieceLoad(null, keepGoing = true)
            state.noteStall(io)
            return PieceLoad(null, sleep(RetryMs))
        } catch (bad: IllegalStateException) {
            state.failHard(bad)
            return PieceLoad(null, keepGoing = false)
        }
    }

    private fun postEnd(cursor: FetchCursor, snap: MixSnap): Boolean {
        if (cursor.postedEnd) return sleep(PollMs)
        val end = HeldPiece(cursor.next, snap.info.dataSize, ByteArray(0), cursor.seen, ended = true)
        if (!buffer.put(end)) return false
        cursor.postedEnd = true
        return sleep(PollMs)
    }

    private fun sleep(delayMs: Long): Boolean {
        try {
            Thread.sleep(delayMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return true
    }

    private companion object {
        const val PollMs = 10L
        const val RetryMs = 200L
    }
}
