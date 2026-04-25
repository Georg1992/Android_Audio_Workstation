package com.georgv.audioworkstation.core.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First-cut [AudioImporter] that accepts PCM WAV files whose sample rate and bit depth already
 * match the target project. Mono/stereo are both accepted and the channel count is reported back
 * via [AudioImportResult.Success.channelMode].
 *
 * Non-matching files are rejected with a specific [AudioImportResult.Failure] so the caller can
 * surface an actionable message. A future implementation can transparently decode and resample
 * using [android.media.MediaExtractor] + [android.media.MediaCodec] behind this same interface.
 */
@Singleton
class WavAudioImporter @Inject constructor() : AudioImporter {

    override suspend fun import(
        source: AudioImportSource,
        destinationPath: String,
        target: AudioImportTarget
    ): AudioImportResult = withContext(Dispatchers.IO) {
        val stream = try {
            source.open()
        } catch (io: IOException) {
            null
        } catch (security: SecurityException) {
            null
        } ?: return@withContext AudioImportResult.Failure.FileNotReadable

        stream.use { input ->
            val header = readWavHeader(input)
                ?: return@withContext AudioImportResult.Failure.InvalidWav

            if (header.audioFormat != PCM_FORMAT) {
                return@withContext AudioImportResult.Failure.UnsupportedEncoding
            }
            if (header.numChannels !in 1..2) {
                return@withContext AudioImportResult.Failure.UnsupportedChannelCount
            }
            if (header.sampleRate != target.sampleRate) {
                return@withContext AudioImportResult.Failure.SampleRateMismatch(
                    expected = target.sampleRate,
                    actual = header.sampleRate
                )
            }
            if (header.bitsPerSample != target.fileBitDepth) {
                return@withContext AudioImportResult.Failure.BitDepthMismatch(
                    expected = target.fileBitDepth,
                    actual = header.bitsPerSample
                )
            }

            val destinationFile = File(destinationPath)
            try {
                destinationFile.parentFile?.mkdirs()
                destinationFile.outputStream().use { output ->
                    writeCanonicalWavHeader(output, header)
                    copyExactly(input, output, header.dataSizeBytes.toLong())
                }
            } catch (writeError: IOException) {
                // Clean up the half-written destination so a retry starts from a clean slate.
                @Suppress("ResultOfMethodCallIgnored")
                destinationFile.delete()
                return@withContext AudioImportResult.Failure.WriteFailed(
                    writeError.message ?: writeError.javaClass.simpleName
                )
            }

            val bytesPerFrame = header.numChannels * (header.bitsPerSample / 8)
            val durationMs = if (bytesPerFrame > 0 && header.sampleRate > 0) {
                (header.dataSizeBytes.toLong() * 1000L) / (bytesPerFrame.toLong() * header.sampleRate)
            } else {
                0L
            }

            AudioImportResult.Success(
                durationMs = durationMs,
                channelMode = if (header.numChannels == 1) ChannelMode.MONO else ChannelMode.STEREO
            )
        }
    }

    /**
     * Reads the RIFF + fmt chunks and locates the data chunk. Leaves [input] positioned right
     * after the data-chunk header so the caller can stream audio bytes directly.
     */
    private fun readWavHeader(input: InputStream): WavHeader? {
        val riffHeader = input.readExactly(12) ?: return null
        if (!riffHeader.startsWith(RIFF_TAG, offset = 0) ||
            !riffHeader.startsWith(WAVE_TAG, offset = 8)
        ) {
            return null
        }

        var audioFormat = -1
        var numChannels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var foundFmt = false

        while (true) {
            val chunkHeader = input.readExactly(8) ?: return null
            val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSize = chunkHeader.readLittleEndianInt(4)
            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16) return null
                    val fmt = input.readExactly(chunkSize) ?: return null
                    audioFormat = fmt.readLittleEndianShort(0).toInt() and 0xFFFF
                    numChannels = fmt.readLittleEndianShort(2).toInt() and 0xFFFF
                    sampleRate = fmt.readLittleEndianInt(4)
                    bitsPerSample = fmt.readLittleEndianShort(14).toInt() and 0xFFFF
                    foundFmt = true
                }
                "data" -> {
                    if (!foundFmt) return null
                    return WavHeader(
                        audioFormat = audioFormat,
                        numChannels = numChannels,
                        sampleRate = sampleRate,
                        bitsPerSample = bitsPerSample,
                        dataSizeBytes = chunkSize
                    )
                }
                else -> {
                    // Unknown ancillary chunk ("LIST", "fact", ...). Skip per WAV spec.
                    if (input.skipExactly(chunkSize.toLong()) == null) return null
                    if (chunkSize % 2 == 1) {
                        if (input.skipExactly(1L) == null) return null
                    }
                }
            }
        }
    }

    /**
     * Writes a canonical RIFF/WAVE header with a single fmt chunk and a single data chunk to
     * [output]. The raw PCM payload is expected to be streamed right after.
     */
    private fun writeCanonicalWavHeader(output: java.io.OutputStream, header: WavHeader) {
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = header.sampleRate * header.numChannels * (header.bitsPerSample / 8)
        val blockAlign = header.numChannels * (header.bitsPerSample / 8)

        buffer.put(RIFF_TAG)
        buffer.putInt(36 + header.dataSizeBytes)
        buffer.put(WAVE_TAG)
        buffer.put(FMT_TAG)
        buffer.putInt(16)
        buffer.putShort(PCM_FORMAT.toShort())
        buffer.putShort(header.numChannels.toShort())
        buffer.putInt(header.sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(header.bitsPerSample.toShort())
        buffer.put(DATA_TAG)
        buffer.putInt(header.dataSizeBytes)

        output.write(buffer.array())
    }

    private data class WavHeader(
        val audioFormat: Int,
        val numChannels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataSizeBytes: Int
    )

    private fun copyExactly(
        input: InputStream,
        output: java.io.OutputStream,
        byteCount: Long
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var remaining = byteCount
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read < 0) throw IOException("Unexpected end of audio data stream.")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private companion object {
        const val PCM_FORMAT = 1
        const val COPY_BUFFER_BYTES = 64 * 1024
        val RIFF_TAG = "RIFF".toByteArray(Charsets.US_ASCII)
        val WAVE_TAG = "WAVE".toByteArray(Charsets.US_ASCII)
        val FMT_TAG = "fmt ".toByteArray(Charsets.US_ASCII)
        val DATA_TAG = "data".toByteArray(Charsets.US_ASCII)
    }
}

private fun InputStream.readExactly(count: Int): ByteArray? {
    val buffer = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(buffer, read, count - read)
        if (n < 0) return null
        read += n
    }
    return buffer
}

private fun InputStream.skipExactly(count: Long): Long? {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped <= 0) {
            val readByte = read()
            if (readByte < 0) return null
            remaining -= 1
        } else {
            remaining -= skipped
        }
    }
    return count
}

private fun ByteArray.startsWith(prefix: ByteArray, offset: Int): Boolean {
    if (offset + prefix.size > size) return false
    for (i in prefix.indices) {
        if (this[offset + i] != prefix[i]) return false
    }
    return true
}

private fun ByteArray.readLittleEndianInt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.readLittleEndianShort(offset: Int): Short =
    (((this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8))).toShort()
