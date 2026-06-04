package com.georgv.audioworkstation.core.audio

import android.content.Context
import android.media.MediaCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaCodecAudioImporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun import(
        source: UriBackedAudioImportSource,
        destinationPath: String,
        target: AudioImportTarget,
    ): AudioImportResult =
        importWithProgress(
            source = source,
            destinationPath = destinationPath,
            target = target,
            estimatedDurationMs = 0L,
            onProgress = {},
        )

    suspend fun importWithProgress(
        source: UriBackedAudioImportSource,
        destinationPath: String,
        target: AudioImportTarget,
        estimatedDurationMs: Long,
        onProgress: (AudioImportProgressUpdate) -> Unit,
    ): AudioImportResult =
        withContext(Dispatchers.IO) {
            if (target.fileBitDepth != TARGET_BIT_DEPTH) {
                Mp3ImportTiming.recordFailure(
                    stage = "bit_depth_check",
                    error = null,
                    partialWavDeleted = false,
                )
                return@withContext AudioImportResult.Failure.BitDepthMismatch(
                    expected = target.fileBitDepth,
                    actual = TARGET_BIT_DEPTH,
                )
            }

            val destinationFile = File(destinationPath)
            val pipeline =
                MediaCodecDecodePipeline(
                    context = context,
                    source = source,
                    destinationFile = destinationFile,
                    target = target,
                    estimatedDurationMs = estimatedDurationMs,
                    onProgress = onProgress,
                )
            try {
                when (val result = pipeline.decode()) {
                    is AudioImportResult.Failure -> {
                        destinationFile.delete()
                        Mp3ImportTiming.recordFailure(
                            stage = "decode_${result.javaClass.simpleName}",
                            error = null,
                            partialWavDeleted = true,
                        )
                        result
                    }
                    is AudioImportResult.Success -> result
                }
            } catch (cancellation: CancellationException) {
                destinationFile.delete()
                Mp3ImportTiming.recordFailure(
                    stage = "decode_cancelled",
                    error = cancellation,
                    partialWavDeleted = true,
                )
                throw cancellation
            } catch (error: UnsupportedPcmEncodingException) {
                destinationFile.delete()
                Mp3ImportTiming.recordFailure(
                    stage = "decode_unsupported_pcm",
                    error = error,
                    partialWavDeleted = true,
                )
                mapDecodeException(error)
            } catch (error: MediaCodec.CodecException) {
                destinationFile.delete()
                Mp3ImportTiming.recordFailure(
                    stage = "decode_codec_error",
                    error = error,
                    partialWavDeleted = true,
                )
                mapDecodeException(error)
            } catch (error: IOException) {
                destinationFile.delete()
                Mp3ImportTiming.recordFailure(
                    stage = "decode_io_error",
                    error = error,
                    partialWavDeleted = true,
                )
                AudioImportResult.Failure.WriteFailed(
                    error.message ?: error.javaClass.simpleName,
                )
            } finally {
                pipeline.release()
            }
        }

    private fun mapDecodeException(error: Exception): AudioImportResult.Failure =
        when (error) {
            is UnsupportedPcmEncodingException -> AudioImportResult.Failure.UnsupportedCodec
            is MediaCodec.CodecException -> AudioImportResult.Failure.CorruptedMedia
            else -> AudioImportResult.Failure.WriteFailed(error.message ?: error.javaClass.simpleName)
        }

    private companion object {
        const val TARGET_BIT_DEPTH = 16
    }
}
