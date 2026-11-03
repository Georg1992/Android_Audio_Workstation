package com.georgv.audioworkstation.ui.playback

import java.io.InputStream

internal class MixPieceReader(
    private val source: MixSource,
    private val state: MixPlayState,
) {
    fun read(url: String, start: Long, endInclusive: Long, exact: Boolean): ByteArray? {
        val input = source.open(url, start, endInclusive)
        if (!state.attach(input)) {
            input.close()
            return null
        }
        try {
            return readBytes(input, (endInclusive - start + 1L).toInt(), exact)
        } finally {
            state.detach(input)
            input.close()
        }
    }

    /** Hands each arriving slice to [onChunk] before the rest of the range is read. */
    fun readChunks(url: String, start: Long, endInclusive: Long, onChunk: (ByteArray) -> Boolean): Boolean {
        val input = source.open(url, start, endInclusive)
        if (!state.attach(input)) {
            input.close()
            return false
        }
        try {
            return readEach(input, (endInclusive - start + 1L).toInt(), onChunk)
        } finally {
            state.detach(input)
            input.close()
        }
    }

    private fun readBytes(input: InputStream, size: Int, exact: Boolean): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(out, offset, size - offset)
            if (count < 0) {
                if (exact || offset == 0) error("mix piece ended early")
                return out.copyOf(offset)
            }
            offset += count
        }
        return out
    }

    private fun readEach(input: InputStream, size: Int, onChunk: (ByteArray) -> Boolean): Boolean {
        var remaining = size
        val scratch = ByteArray(minOf(MixPieces.PlayChunk, size.coerceAtLeast(1)))
        while (remaining > 0) {
            val count = input.read(scratch, 0, minOf(scratch.size, remaining))
            if (count < 0) error("mix piece ended early")
            if (count == 0) continue
            if (!onChunk(scratch.copyOf(count))) return false
            remaining -= count
        }
        return true
    }
}
