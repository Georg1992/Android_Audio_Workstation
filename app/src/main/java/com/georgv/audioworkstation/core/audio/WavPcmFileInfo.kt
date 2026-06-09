package com.georgv.audioworkstation.core.audio

import java.io.File
import java.io.RandomAccessFile

data class WavPcmFileInfo(
    val channelCount: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val blockAlign: Int,
    val dataOffset: Long,
    val dataSize: Long,
) {
    val frameCount: Long = if (blockAlign > 0) dataSize / blockAlign else 0L

    val durationMs: Long
        get() =
            if (sampleRateHz > 0 && frameCount > 0L) {
                (frameCount * 1_000L + sampleRateHz.toLong() - 1L) / sampleRateHz.toLong()
            } else {
                0L
            }

    val isSupportedProjectPcm: Boolean =
        channelCount in 1..2 &&
            bitsPerSample == 16 &&
            blockAlign == channelCount * 2
}

fun readWavPcmFileInfo(path: String): WavPcmFileInfo? {
    val file = File(path)
    if (!file.isFile) return null
    return runCatching {
        RandomAccessFile(file, "r").use { wav -> wav.readWavPcmFileInfo() }
    }.getOrNull()
}

private fun RandomAccessFile.readWavPcmFileInfo(): WavPcmFileInfo? {
    if (length() < 44L) return null
    if (readAscii(4) != "RIFF") return null
    skipBytes(4)
    if (readAscii(4) != "WAVE") return null

    var audioFormat = 0
    var channelCount = 0
    var sampleRateHz = 0
    var bitsPerSample = 0
    var blockAlign = 0
    var dataOffset = -1L
    var dataSize = 0L

    while (filePointer + 8L <= length()) {
        val chunkId = readAscii(4)
        val chunkSize = readUInt32Le()
        val chunkStart = filePointer
        when (chunkId) {
            "fmt " -> {
                if (chunkSize < 16L) return null
                audioFormat = readUInt16Le()
                channelCount = readUInt16Le()
                sampleRateHz = readUInt32Le().toInt()
                skipBytes(4)
                blockAlign = readUInt16Le()
                bitsPerSample = readUInt16Le()
            }
            "data" -> {
                dataOffset = filePointer
                dataSize = chunkSize
            }
        }
        seek(chunkStart + chunkSize + (chunkSize and 1L))
        if (dataOffset >= 0L && audioFormat != 0) {
            if (audioFormat != 1) return null
            return WavPcmFileInfo(
                channelCount = channelCount,
                sampleRateHz = sampleRateHz,
                bitsPerSample = bitsPerSample,
                blockAlign = blockAlign,
                dataOffset = dataOffset,
                dataSize = dataSize,
            )
        }
    }
    return null
}

private fun RandomAccessFile.readAscii(length: Int): String {
    val bytes = ByteArray(length)
    readFully(bytes)
    return bytes.decodeToString()
}

private fun RandomAccessFile.readUInt16Le(): Int {
    val b0 = read()
    val b1 = read()
    if (b0 < 0 || b1 < 0) return 0
    return b0 or (b1 shl 8)
}

private fun allBytesAvailable(vararg bytes: Int): Boolean = bytes.all { it >= 0 }

private fun RandomAccessFile.readUInt32Le(): Long {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    if (!allBytesAvailable(b0, b1, b2, b3)) return 0L
    return (b0.toLong() and 0xFF) or
        ((b1.toLong() and 0xFF) shl 8) or
        ((b2.toLong() and 0xFF) shl 16) or
        ((b3.toLong() and 0xFF) shl 24)
}

fun msToFramePosition(ms: Long, sampleRateHz: Int): Long {
    if (ms <= 0L || sampleRateHz <= 0) return 0L
    return (ms * sampleRateHz.toLong()) / 1_000L
}

fun framePositionToMs(frames: Long, sampleRateHz: Int): Long {
    if (frames <= 0L || sampleRateHz <= 0) return 0L
    return (frames * 1_000L + sampleRateHz.toLong() - 1L) / sampleRateHz.toLong()
}

internal fun resultingClipDurationMs(
    oldDurationMs: Long,
    spliceStartInClipMs: Long,
    recordedDurationMs: Long,
): Long {
    val recordEndMs = spliceStartInClipMs + recordedDurationMs
    return if (spliceStartInClipMs > oldDurationMs) {
        recordEndMs
    } else {
        maxOf(recordEndMs, oldDurationMs)
    }
}
