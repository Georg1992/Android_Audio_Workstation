package com.georgv.audioworkstation.core.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
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
class WavAudioImporter(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioImporter {

    override suspend fun import(
        source: AudioImportSource,
        destinationPath: String,
        target: AudioImportTarget,
    ): AudioImportResult =
        withContext(ioDispatcher) {
            val stream = openImportStream(source) ?: return@withContext AudioImportResult.Failure.FileNotReadable
            stream.use { input ->
                importFromOpenStream(input, destinationPath, target)
            }
        }

    private fun importFromOpenStream(
        input: InputStream,
        destinationPath: String,
        target: AudioImportTarget,
    ): AudioImportResult {
        val header = parseWavHeader(input) ?: return AudioImportResult.Failure.InvalidWav
        validateParsedHeader(header, target)?.let { return it }

        val destinationFile = File(destinationPath)
        return try {
            destinationFile.parentFile?.mkdirs()
            destinationFile.outputStream().use { output ->
                writeCanonicalWavHeader(output, header)
                copyExactly(input, output, header.dataSizeBytes.toLong())
            }
            buildSuccessResult(header)
        } catch (writeError: IOException) {
            @Suppress("ResultOfMethodCallIgnored")
            destinationFile.delete()
            AudioImportResult.Failure.WriteFailed(
                writeError.message ?: writeError.javaClass.simpleName,
            )
        }
    }

    private fun validateParsedHeader(
        header: ParsedWavHeader,
        target: AudioImportTarget,
    ): AudioImportResult.Failure? =
        when {
            header.audioFormat != PCM_FORMAT -> AudioImportResult.Failure.UnsupportedEncoding
            header.numChannels !in 1..2 -> AudioImportResult.Failure.UnsupportedChannelCount
            header.sampleRate != target.sampleRate ->
                AudioImportResult.Failure.SampleRateMismatch(
                    expected = target.sampleRate,
                    actual = header.sampleRate,
                )
            header.bitsPerSample != target.fileBitDepth ->
                AudioImportResult.Failure.BitDepthMismatch(
                    expected = target.fileBitDepth,
                    actual = header.bitsPerSample,
                )
            else -> null
        }

    private fun buildSuccessResult(header: ParsedWavHeader): AudioImportResult.Success {
        val bytesPerFrame = header.numChannels * (header.bitsPerSample / BITS_PER_BYTE)
        val durationMs =
            if (bytesPerFrame > 0 && header.sampleRate > 0) {
                (header.dataSizeBytes.toLong() * MS_PER_SECOND) /
                    (bytesPerFrame.toLong() * header.sampleRate)
            } else {
                0L
            }
        return AudioImportResult.Success(
            durationMs = durationMs,
            channelMode = if (header.numChannels == 1) ChannelMode.MONO else ChannelMode.STEREO,
            channelCount = header.numChannels,
        )
    }

    private fun writeCanonicalWavHeader(output: java.io.OutputStream, header: ParsedWavHeader) {
        WavHeaderWriter.writeHeader(
            output = output,
            numChannels = header.numChannels,
            sampleRate = header.sampleRate,
            bitsPerSample = header.bitsPerSample,
            dataSizeBytes = header.dataSizeBytes,
        )
    }

    private fun copyExactly(
        input: InputStream,
        output: java.io.OutputStream,
        byteCount: Long,
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
        const val BITS_PER_BYTE = 8
        const val MS_PER_SECOND = 1000L
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

private fun openImportStream(source: AudioImportSource): InputStream? =
    runCatching { source.open() }.getOrNull()
