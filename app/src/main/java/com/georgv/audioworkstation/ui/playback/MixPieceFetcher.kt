package com.georgv.audioworkstation.ui.playback

import java.io.IOException

internal class PieceLoad(val bytes: ByteArray?, val keepGoing: Boolean)

internal class FetchCursor(
    var next: Int = 0,
    var seen: Int = -1,
    var postedEnd: Boolean = false,
    var origin: Long = IndexPath,
    var lead: Boolean = false,
) {
    internal companion object {
        const val IndexPath = -1L
    }
}

private class LeadMark(var pcm: Long)

internal class MixPieceFetcher(
    private val reader: MixPieceReader,
    private val buffer: MixPieceBuffer,
    private val state: MixPlayState,
    private val ahead: Int,
) {
    fun run() {
        val snap = state.snapshot()
        if (snap != null) buffer.limitBytes(snap.pieceBytes)
        val cursor = FetchCursor()
        while (step(cursor)) Unit
    }

    private fun step(cursor: FetchCursor): Boolean {
        val snap = state.snapshot() ?: return false
        if (!snap.playing) return sleep(PollMs)
        if (snap.generation != cursor.seen) retarget(cursor, snap)
        if (bufferedEnough(snap)) return sleep(PollMs)
        if (cursor.origin >= 0L) return fetchFromOrigin(cursor, snap)
        if (cursor.next < snap.playIndex) cursor.next = snap.playIndex
        return fetch(cursor, snap)
    }

    private fun retarget(cursor: FetchCursor, snap: MixSnap) {
        cursor.seen = snap.generation
        cursor.postedEnd = false
        cursor.lead = !snap.holding
        cursor.origin = if (snap.holding) FetchCursor.IndexPath else snap.pcmByte
        cursor.next = if (snap.holding) snap.playIndex + 1 else snap.playIndex
    }

    private fun bufferedEnough(snap: MixSnap): Boolean {
        val piece = snap.pieceBytes
        if (piece <= 0) return false
        val slots = if (snap.holding) ahead else ahead + 1
        return buffer.queuedBytes() >= slots * piece
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

    private fun fetchFromOrigin(cursor: FetchCursor, snap: MixSnap): Boolean {
        val span = MixPieces.spanFrom(snap.info, snap.pieceBytes, cursor.origin) ?: return postEnd(cursor, snap)
        if (cursor.lead) return streamLead(cursor, snap, span)
        val loaded = load(snap, span, cursor.seen)
        val bytes = loaded.bytes ?: return loaded.keepGoing
        val piece = HeldPiece(span.index, span.pcmStart, bytes, cursor.seen, ended = false)
        if (!buffer.put(piece)) return false
        if (state.snapshot()?.generation != cursor.seen) return true
        cursor.origin = span.pcmStart + span.size
        state.noteReady()
        return true
    }

    private fun streamLead(cursor: FetchCursor, snap: MixSnap, span: MixSpan): Boolean {
        val lead = LeadMark(span.pcmStart)
        return try {
            val finished = reader.readChunks(snap.url, span.fileStart, span.fileEndInclusive) { bytes ->
                val accepted = offerChunk(cursor, span, lead.pcm, bytes)
                if (accepted) lead.pcm += bytes.size
                accepted
            }
            finishLead(cursor, span, lead.pcm, finished)
        } catch (io: IOException) {
            stallLead(cursor, span, lead.pcm, io)
        } catch (bad: IllegalStateException) {
            state.failHard(bad)
            false
        }
    }

    private fun offerChunk(cursor: FetchCursor, span: MixSpan, pcm: Long, bytes: ByteArray): Boolean {
        if (state.snapshot()?.generation != cursor.seen) return false
        return buffer.put(HeldPiece(span.index, pcm, bytes, cursor.seen, ended = false))
    }

    private fun finishLead(cursor: FetchCursor, span: MixSpan, pcm: Long, finished: Boolean): Boolean {
        if (state.snapshot()?.generation != cursor.seen) return true
        if (!finished) {
            cursor.origin = pcm
            return sleep(PollMs)
        }
        state.noteReady()
        cursor.lead = false
        cursor.origin = span.pcmStart + span.size
        return true
    }

    private fun stallLead(cursor: FetchCursor, span: MixSpan, pcm: Long, io: IOException): Boolean {
        if (state.snapshot()?.generation != cursor.seen) return true
        state.noteStall(io)
        if (pcm != span.pcmStart) cursor.origin = pcm
        return sleep(RetryMs)
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
