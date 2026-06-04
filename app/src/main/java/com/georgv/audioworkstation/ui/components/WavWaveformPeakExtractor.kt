package com.georgv.audioworkstation.ui.components

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

private const val DefaultWaveformPeakCount = 72
private const val WavAudioFormatPcm = 1
private const val WavAudioFormatFloat = 3
private const val WavChunkReadFrames = 4096

open class WavWaveformPeakExtractor(
    private val targetPeakCount: Int = DefaultWaveformPeakCount,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cache = mutableMapOf<String, WaveformPeaks>()

    open suspend fun extract(wavPath: String): WaveformPeaks? =
        withContext(ioDispatcher) {
            val path = wavPath.trim()
            if (path.isEmpty()) return@withContext null
            val fingerprint = wavFileContentFingerprint(path) ?: return@withContext null
            cache[fingerprint]?.let { return@withContext it }
            dropStaleCacheEntriesForPath(fingerprint)
            val peaks = readPeaks(path) ?: return@withContext null
            cache[fingerprint] = peaks
            peaks
        }

    /** Returns cached peaks for [wavPath] without reading the file. */
    fun peekCachedPeaks(wavPath: String): WaveformPeaks? {
        val fingerprint = wavFileContentFingerprint(wavPath.trim()) ?: return null
        return cache[fingerprint]
    }

    private fun dropStaleCacheEntriesForPath(fingerprint: String) {
        val prefix = wavFilePathPrefix(fingerprint) + "|"
        cache.keys.removeAll { it.startsWith(prefix) && it != fingerprint }
    }

    private fun readPeaks(wavPath: String): WaveformPeaks? {
        val file = File(wavPath)
        if (!file.isFile || targetPeakCount <= 0) return null

        return runCatching {
            RandomAccessFile(file, "r").use { wav ->
                val info = wav.readWavInfo() ?: return null
                if (!info.isSupported) return null

                return if (info.channelCount == 1) {
                    wav.readMonoPeaks(info)
                } else {
                    wav.readStereoPeaks(info)
                }
            }
        }.getOrNull()
    }

    private fun RandomAccessFile.readMonoPeaks(info: WavInfo): WaveformPeaks? {
        val buckets = Array(targetPeakCount) { WaveformBucketAccumulator() }
        val framesPerPeak = framesPerPeakBucket(info.frameCount)
        val bytesPerFrame = info.blockAlign
        val buffer = ByteArray(WavChunkReadFrames * bytesPerFrame)

        seek(info.dataOffset)
        var framesSeen = 0L
        var bytesRemaining = info.dataSize
        while (bytesRemaining > 0L && framesSeen < info.frameCount) {
            val bytesToRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
            val bytesRead = read(buffer, 0, bytesToRead)
            if (bytesRead <= 0) break

            val framesRead = bytesRead / bytesPerFrame
            for (frame in 0 until framesRead) {
                val peakIndex = peakBucketIndex(framesSeen, framesPerPeak)
                val frameOffset = frame * bytesPerFrame
                val sample =
                    buffer.readFrameSamples(
                        frameOffset = frameOffset,
                        channelCount = 1,
                        bitsPerSample = info.bitsPerSample,
                        audioFormat = info.audioFormat,
                    )[0]
                buckets[peakIndex].addSample(sample)
                framesSeen++
            }
            bytesRemaining -= bytesRead.toLong()
        }

        val peaks = normalizePeaks(buckets.map { it.visualAmplitude() })
        return WaveformPeaks(
            amplitudes = peaks,
            sourceDurationMs = info.sourceDurationMs,
        )
    }

    private fun RandomAccessFile.readStereoPeaks(info: WavInfo): WaveformPeaks? {
        val leftBuckets = Array(targetPeakCount) { WaveformBucketAccumulator() }
        val rightBuckets = Array(targetPeakCount) { WaveformBucketAccumulator() }
        val framesPerPeak = framesPerPeakBucket(info.frameCount)
        val bytesPerFrame = info.blockAlign
        val buffer = ByteArray(WavChunkReadFrames * bytesPerFrame)

        seek(info.dataOffset)
        var framesSeen = 0L
        var bytesRemaining = info.dataSize
        while (bytesRemaining > 0L && framesSeen < info.frameCount) {
            val bytesToRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
            val bytesRead = read(buffer, 0, bytesToRead)
            if (bytesRead <= 0) break

            val framesRead = bytesRead / bytesPerFrame
            for (frame in 0 until framesRead) {
                val peakIndex = peakBucketIndex(framesSeen, framesPerPeak)
                val frameOffset = frame * bytesPerFrame
                val samples =
                    buffer.readFrameSamples(
                        frameOffset = frameOffset,
                        channelCount = 2,
                        bitsPerSample = info.bitsPerSample,
                        audioFormat = info.audioFormat,
                    )
                leftBuckets[peakIndex].addSample(samples[0])
                rightBuckets[peakIndex].addSample(samples[1])
                framesSeen++
            }
            bytesRemaining -= bytesRead.toLong()
        }

        val leftPeaks = leftBuckets.map { it.visualAmplitude() }
        val rightPeaks = rightBuckets.map { it.visualAmplitude() }
        val normalized = normalizeStereoPeaks(leftPeaks, rightPeaks)
        return WaveformPeaks(
            amplitudes = emptyList(),
            leftAmplitudes = normalized.first,
            rightAmplitudes = normalized.second,
            sourceDurationMs = info.sourceDurationMs,
        )
    }

    private fun framesPerPeakBucket(frameCount: Long): Long =
        ceil(frameCount.toDouble() / targetPeakCount.toDouble())
            .toLong()
            .coerceAtLeast(1L)

    private fun peakBucketIndex(framesSeen: Long, framesPerPeak: Long): Int =
        (framesSeen / framesPerPeak)
            .coerceIn(0L, (targetPeakCount - 1).toLong())
            .toInt()

    private fun normalizePeaks(raw: List<Float>): List<Float> {
        val peaks = raw.toMutableList()
        val maxPeak = peaks.maxOrNull() ?: 0f
        if (maxPeak > 0f) {
            for (index in peaks.indices) {
                peaks[index] = (peaks[index] / maxPeak).coerceIn(0f, 1f)
            }
        }
        return peaks
    }

    /** Each channel is normalized to its own peak so balanced stereo displays with equal scale. */
    private fun normalizeStereoPeaks(
        left: List<Float>,
        right: List<Float>,
    ): Pair<List<Float>, List<Float>> =
        normalizePeaks(left) to normalizePeaks(right)
}

private data class WavInfo(
    val audioFormat: Int,
    val channelCount: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val blockAlign: Int,
    val dataOffset: Long,
    val dataSize: Long,
) {
    val frameCount: Long = if (blockAlign > 0) dataSize / blockAlign else 0L
    val sourceDurationMs: Long
        get() =
            if (sampleRateHz > 0 && frameCount > 0L) {
                (frameCount * 1000L + sampleRateHz.toLong() - 1L) / sampleRateHz.toLong()
            } else {
                0L
            }
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
            return WavInfo(
                audioFormat = audioFormat,
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

private fun RandomAccessFile.readUInt32Le(): Long {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    if (readBytesAllEof(b0, b1, b2, b3)) return 0L
    return (b0.toLong() or
        (b1.toLong() shl 8) or
        (b2.toLong() shl 16) or
        (b3.toLong() shl UInt32LeByte3Shift)) and UInt32LeMask
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
        8 -> (((this[offset].toInt() and 0xFF) - Pcm8BitCenter) / Pcm8BitScale).coerceIn(-1f, 1f)
        16 -> (readInt16Le(offset) / Pcm16BitScale).coerceIn(-1f, 1f)
        24 -> (readInt24Le(offset) / Pcm24BitScale).coerceIn(-1f, 1f)
        32 -> (readInt32Le(offset) / Pcm32BitScale).coerceIn(-1f, 1f)
        else -> 0f
    }

private fun ByteArray.readInt16Le(offset: Int): Int {
    val value = (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)
    return if ((value and Int16SignBit) != 0) value or Int16SignExtend else value
}

private fun ByteArray.readInt24Le(offset: Int): Int {
    val value = (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)
    return if ((value and Int24SignBit) != 0) value or Int24SignExtend else value
}

private fun ByteArray.readInt32Le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl UInt32LeByte3Shift)

private fun readBytesAllEof(b0: Int, b1: Int, b2: Int, b3: Int): Boolean =
    b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0

private const val UInt32LeByte3Shift = 24
private const val UInt32LeMask = 0xFFFF_FFFFL
private const val Pcm8BitCenter = 128
private const val Pcm8BitScale = 128f
private const val Pcm16BitScale = 32768f
private const val Pcm24BitScale = 8_388_608f
private const val Pcm32BitScale = 2_147_483_648f
private const val Int16SignBit = 0x8000
private const val Int16SignExtend = -0x10000
private const val Int24SignBit = 0x800000
private const val Int24SignExtend = -0x1000000
