package com.georgv.audioworkstation.ui.components

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

private const val DefaultWaveformPeakCount = 72
private const val WavAudioFormatPcm = 1
private const val WavAudioFormatFloat = 3
private const val WavChunkReadFrames = 4096
private const val WaveformPeakWeight = 0.35f
private const val WaveformRmsWeight = 0.65f

class WavWaveformPeakExtractor(
    private val targetPeakCount: Int = DefaultWaveformPeakCount,
) {
    private val cache = mutableMapOf<String, WaveformPeaks>()

    suspend fun extract(wavPath: String): WaveformPeaks? =
        withContext(Dispatchers.IO) {
            val path = wavPath.trim()
            if (path.isEmpty()) return@withContext null
            cache[path]?.let { return@withContext it }
            val peaks = readPeaks(path) ?: return@withContext null
            cache[path] = peaks
            peaks
        }

    private fun readPeaks(wavPath: String): WaveformPeaks? {
        val file = File(wavPath)
        if (!file.isFile || targetPeakCount <= 0) return null

        return runCatching {
            RandomAccessFile(file, "r").use { wav ->
                val info = wav.readWavInfo() ?: return null
                if (!info.isSupported) return null

                val buckets = Array(targetPeakCount) { WaveformBucketAccumulator() }
                val framesPerPeak = ceil(info.frameCount.toDouble() / targetPeakCount.toDouble())
                    .toLong()
                    .coerceAtLeast(1L)
                val bytesPerFrame = info.blockAlign
                val buffer = ByteArray(WavChunkReadFrames * bytesPerFrame)

                wav.seek(info.dataOffset)
                var framesSeen = 0L
                var bytesRemaining = info.dataSize
                while (bytesRemaining > 0L && framesSeen < info.frameCount) {
                    val bytesToRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                    val bytesRead = wav.read(buffer, 0, bytesToRead)
                    if (bytesRead <= 0) break

                    val framesRead = bytesRead / bytesPerFrame
                    for (frame in 0 until framesRead) {
                        val peakIndex = (framesSeen / framesPerPeak)
                            .coerceIn(0L, (targetPeakCount - 1).toLong())
                            .toInt()
                        val frameOffset = frame * bytesPerFrame
                        buckets[peakIndex].addFrame(
                            buffer.readFrameSamples(
                                frameOffset = frameOffset,
                                channelCount = info.channelCount,
                                bitsPerSample = info.bitsPerSample,
                                audioFormat = info.audioFormat,
                            )
                        )
                        framesSeen++
                    }
                    bytesRemaining -= bytesRead.toLong()
                }

                val peaks = FloatArray(targetPeakCount) { index ->
                    buckets[index].visualAmplitude()
                }
                val maxPeak = peaks.maxOrNull() ?: 0f
                if (maxPeak > 0f) {
                    for (index in peaks.indices) {
                        peaks[index] = (peaks[index] / maxPeak).coerceIn(0f, 1f)
                    }
                }
                WaveformPeaks(peaks.toList())
            }
        }.getOrNull()
    }
}

private class WaveformBucketAccumulator {
    private var peak = 0f
    private var sumSquares = 0.0
    private var sampleCount = 0

    fun addFrame(samples: FloatArray) {
        for (sample in samples) {
            val absSample = abs(sample)
            peak = max(peak, absSample)
            sumSquares += (sample * sample).toDouble()
            sampleCount += 1
        }
    }

    fun visualAmplitude(): Float {
        if (sampleCount == 0) return 0f
        val rms = sqrt(sumSquares / sampleCount.toDouble()).toFloat().coerceIn(0f, 1f)
        return (WaveformPeakWeight * peak + WaveformRmsWeight * rms).coerceIn(0f, 1f)
    }
}

private data class WavInfo(
    val audioFormat: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val blockAlign: Int,
    val dataOffset: Long,
    val dataSize: Long,
) {
    val frameCount: Long = if (blockAlign > 0) dataSize / blockAlign else 0L
    val isSupported: Boolean =
        channelCount in 1..2 &&
            blockAlign > 0 &&
            dataSize > 0L &&
            (
                audioFormat == WavAudioFormatPcm &&
                    bitsPerSample in setOf(8, 16, 24, 32) ||
                    audioFormat == WavAudioFormatFloat &&
                    bitsPerSample == 32
                )
}

private fun RandomAccessFile.readWavInfo(): WavInfo? {
    if (length() < 44L) return null
    if (readAscii(4) != "RIFF") return null
    skipBytes(4)
    if (readAscii(4) != "WAVE") return null

    var audioFormat = 0
    var channelCount = 0
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
                skipBytes(4)
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
            return WavInfo(
                audioFormat = audioFormat,
                channelCount = channelCount,
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

private fun RandomAccessFile.readUInt32Le(): Long {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return 0L
    return (b0.toLong() or
        (b1.toLong() shl 8) or
        (b2.toLong() shl 16) or
        (b3.toLong() shl 24)) and 0xFFFF_FFFFL
}

private fun ByteArray.readFrameSamples(
    frameOffset: Int,
    channelCount: Int,
    bitsPerSample: Int,
    audioFormat: Int,
): FloatArray {
    val bytesPerSample = bitsPerSample / 8
    val samples = FloatArray(channelCount)
    for (channel in 0 until channelCount) {
        val offset = frameOffset + channel * bytesPerSample
        samples[channel] =
            if (audioFormat == WavAudioFormatFloat) {
                Float.fromBits(readInt32Le(offset)).coerceIn(-1f, 1f)
            } else {
                readPcmSample(offset, bitsPerSample)
            }
    }
    return samples
}

private fun ByteArray.readPcmSample(offset: Int, bitsPerSample: Int): Float =
    when (bitsPerSample) {
        8 -> (((this[offset].toInt() and 0xFF) - 128) / 128f).coerceIn(-1f, 1f)
        16 -> (readInt16Le(offset) / 32768f).coerceIn(-1f, 1f)
        24 -> (readInt24Le(offset) / 8_388_608f).coerceIn(-1f, 1f)
        32 -> (readInt32Le(offset) / 2_147_483_648f).coerceIn(-1f, 1f)
        else -> 0f
    }

private fun ByteArray.readInt16Le(offset: Int): Int {
    val value = (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)
    return if ((value and 0x8000) != 0) value or -0x10000 else value
}

private fun ByteArray.readInt24Le(offset: Int): Int {
    val value = (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)
    return if ((value and 0x800000) != 0) value or -0x1000000 else value
}

private fun ByteArray.readInt32Le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
