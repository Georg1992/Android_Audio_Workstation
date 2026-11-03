package com.georgv.audioworkstation.ui.playback

import com.georgv.audioworkstation.core.audio.WavPcmFileInfo

internal class MixSpan(
    val index: Int,
    val fileStart: Long,
    val fileEndInclusive: Long,
    val pcmStart: Long,
    val size: Int,
)

internal object MixPieces {
    fun pieceBytes(sampleRateHz: Int, blockAlign: Int): Int {
        val frames = PieceMs * sampleRateHz / MsPerSecond
        val bytes = frames * blockAlign
        return bytes.coerceAtLeast(blockAlign.toLong()).toInt()
    }

    fun indexAt(pcmByte: Long, pieceBytes: Int): Int {
        if (pieceBytes <= 0) return 0
        return (pcmByte / pieceBytes).toInt()
    }

    /** Index entry for piece [index]. Audio for that entry is stored separately. */
    fun span(info: WavPcmFileInfo, pieceBytes: Int, index: Int): MixSpan? {
        if (pieceBytes <= 0 || index < 0) return null
        val pcmStart = index.toLong() * pieceBytes
        if (pcmStart >= info.dataSize) return null
        val size = minOf(pieceBytes.toLong(), info.dataSize - pcmStart).toInt()
        if (size <= 0) return null
        val fileStart = info.dataOffset + pcmStart
        return MixSpan(
            index = index,
            fileStart = fileStart,
            fileEndInclusive = fileStart + size - 1,
            pcmStart = pcmStart,
            size = size,
        )
    }

    fun pcmByte(info: WavPcmFileInfo, positionMs: Int): Long {
        if (info.sampleRateHz <= 0 || info.blockAlign <= 0) return 0L
        val duration = info.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val clamped = positionMs.coerceIn(0, duration)
        val frames = clamped.toLong() * info.sampleRateHz / MsPerSecond
        val frameCount = info.dataSize / info.blockAlign
        return frames.coerceAtMost(frameCount) * info.blockAlign
    }

    const val AheadPieces = 2
    const val HeaderEnd = 4095L
    const val PlayChunk = 4096
    private const val PieceMs = 2000L
    private const val MsPerSecond = 1000L
}
