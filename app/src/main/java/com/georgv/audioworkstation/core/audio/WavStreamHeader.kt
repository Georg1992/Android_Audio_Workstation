package com.georgv.audioworkstation.core.audio

import java.io.InputStream

internal object WavStreamHeader {
    fun read(input: InputStream): WavPcmFileInfo? {
        if (readAscii(input, MarkBytes) != Riff) return null
        if (readUInt32Le(input) == null) return null
        if (readAscii(input, MarkBytes) != Wave) return null
        return readChunks(input)
    }

    private fun readChunks(input: InputStream): WavPcmFileInfo? {
        var consumed = PrefixBytes
        var fmt: FmtInfo? = null
        while (consumed < MaxHeaderBytes) {
            val chunkId = readAscii(input, MarkBytes) ?: return null
            val chunkSize = readUInt32Le(input) ?: return null
            consumed += ChunkHeaderBytes
            val found = when (chunkId) {
                Fmt -> {
                    fmt = readFmt(input, chunkSize) ?: return null
                    consumed += chunkSize
                    consumed = pad(input, chunkSize, consumed) ?: return null
                    null
                }
                Data -> pcmChunk(fmt, consumed, chunkSize)
                else -> {
                    if (!skipFully(input, chunkSize)) return null
                    consumed += chunkSize
                    consumed = pad(input, chunkSize, consumed) ?: return null
                    null
                }
            }
            if (chunkId == Data) return found
        }
        return null
    }

    private fun pcmChunk(fmt: FmtInfo?, dataOffset: Long, dataSize: Long): WavPcmFileInfo? {
        val parsed = fmt ?: return null
        if (parsed.audioFormat != PcmFormat) return null
        return WavPcmFileInfo(
            channelCount = parsed.channelCount,
            sampleRateHz = parsed.sampleRateHz,
            bitsPerSample = parsed.bitsPerSample,
            blockAlign = parsed.blockAlign,
            dataOffset = dataOffset,
            dataSize = dataSize,
        )
    }

    private fun readFmt(input: InputStream, chunkSize: Long): FmtInfo? {
        if (chunkSize < FmtBytes) return null
        val audioFormat = readUInt16Le(input) ?: return null
        val channelCount = readUInt16Le(input) ?: return null
        val sampleRateHz = readUInt32Le(input)?.toInt() ?: return null
        if (!skipFully(input, RateSkipBytes)) return null
        val blockAlign = readUInt16Le(input) ?: return null
        val bitsPerSample = readUInt16Le(input) ?: return null
        val extra = chunkSize - FmtBytes
        if (extra > 0L && !skipFully(input, extra)) return null
        return FmtInfo(
            audioFormat = audioFormat,
            channelCount = channelCount,
            sampleRateHz = sampleRateHz,
            bitsPerSample = bitsPerSample,
            blockAlign = blockAlign,
        )
    }

    private fun pad(input: InputStream, chunkSize: Long, consumed: Long): Long? {
        if ((chunkSize and 1L) != 1L) return consumed
        if (!skipFully(input, 1)) return null
        return consumed + 1
    }

    private fun readAscii(input: InputStream, length: Int): String? {
        val bytes = ByteArray(length)
        if (!readFully(input, bytes)) return null
        return bytes.decodeToString()
    }

    private fun readUInt16Le(input: InputStream): Int? {
        val bytes = ByteArray(ShortBytes)
        if (!readFully(input, bytes)) return null
        return (bytes[0].toInt() and ByteMask) or ((bytes[1].toInt() and ByteMask) shl ByteShift)
    }

    private fun readUInt32Le(input: InputStream): Long? {
        val bytes = ByteArray(IntBytes)
        if (!readFully(input, bytes)) return null
        return (bytes[0].toLong() and LongMask) or
            ((bytes[1].toLong() and LongMask) shl ByteShift) or
            ((bytes[2].toLong() and LongMask) shl ShortShift) or
            ((bytes[3].toLong() and LongMask) shl IntShift)
    }

    private fun readFully(input: InputStream, bytes: ByteArray): Boolean {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) return false
            offset += count
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long): Boolean {
        var left = count
        val scratch = ByteArray(SkipBytes)
        while (left > 0L) {
            val want = minOf(left, SkipBytes.toLong()).toInt()
            val countRead = input.read(scratch, 0, want)
            if (countRead < 0) return false
            left -= countRead
        }
        return true
    }

    private class FmtInfo(
        val audioFormat: Int,
        val channelCount: Int,
        val sampleRateHz: Int,
        val bitsPerSample: Int,
        val blockAlign: Int,
    )

    private const val Riff = "RIFF"
    private const val Wave = "WAVE"
    private const val Fmt = "fmt "
    private const val Data = "data"
    private const val PcmFormat = 1
    private const val ByteMask = 0xFF
    private const val LongMask = 0xFFL
    private const val MarkBytes = 4
    private const val ShortBytes = 2
    private const val IntBytes = 4
    private const val ByteShift = 8
    private const val ShortShift = 16
    private const val IntShift = 24
    private const val PrefixBytes = 12L
    private const val ChunkHeaderBytes = 8L
    private const val FmtBytes = 16L
    private const val RateSkipBytes = 4L
    private const val SkipBytes = 256
    private const val MaxHeaderBytes = 65_536L
}
