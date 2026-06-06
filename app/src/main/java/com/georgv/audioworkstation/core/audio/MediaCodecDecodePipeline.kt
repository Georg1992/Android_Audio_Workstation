package com.georgv.audioworkstation.core.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.IOException

/**
 * Decodes compressed audio (MP3/AAC/etc.) to a canonical project WAV via MediaCodec.
 *
 * Pipeline stages:
 * 1. MediaExtractor setup and track selection
 * 2. MediaCodec decode loop (batched compressed input for MP3)
 * 3. PCM16 WAV streaming write
 *
 * Exact waveform peaks are intentionally **not** produced here; they run post-READY from the
 * finalized WAV via [com.georgv.audioworkstation.core.audio.waveform.WavWaveformPeakExtractor].
 */
internal class MediaCodecDecodePipeline(
    private val context: Context,
    private val source: UriBackedAudioImportSource,
    private val destinationFile: File,
    private val target: AudioImportTarget,
    private val estimatedDurationMs: Long,
    private val onProgress: (AudioImportProgressUpdate) -> Unit,
) {
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var lastProgressEmitElapsedMs = 0L

    fun decode(): AudioImportResult {
        Mp3ImportTiming.startStage("extractor_setup")
        extractor = MediaExtractor()
        if (!attachExtractorDataSource(extractor!!)) {
            Mp3ImportTiming.stopStage("extractor_setup")
            Mp3ImportTiming.recordFailure(stage = "extractor_setup", error = null, partialWavDeleted = false)
            return AudioImportResult.Failure.FileNotReadable
        }
        Mp3ImportTiming.stopStage("extractor_setup")

        Mp3ImportTiming.startStage("track_selection")
        val trackIndex = CompressedMediaTrack.selectAudioTrackIndex(extractor!!)
        if (trackIndex == null) {
            Mp3ImportTiming.stopStage("track_selection")
            Mp3ImportTiming.recordFailure(stage = "track_selection", error = null, partialWavDeleted = false)
            return AudioImportResult.Failure.NoAudioTrack
        }

        extractor!!.selectTrack(trackIndex)
        val trackFormat = extractor!!.getTrackFormat(trackIndex)
        val trackInfo = CompressedMediaTrack.readTrackInfo(trackFormat)
        Mp3ImportTiming.stopStage("track_selection")
        if (trackInfo == null) {
            Mp3ImportTiming.recordFailure(stage = "track_selection", error = null, partialWavDeleted = false)
            return AudioImportResult.Failure.UnsupportedCodec
        }
        if (trackInfo.channelCount !in 1..2) {
            Mp3ImportTiming.recordFailure(stage = "track_selection", error = null, partialWavDeleted = false)
            return AudioImportResult.Failure.UnsupportedChannelCount
        }

        val batchingEnabled = CompressedImportDecodeConfig.batchingEnabledFor(trackInfo.mimeType)
        Mp3ImportTiming.setInputBatchingEnabled(batchingEnabled)
        Mp3ImportTiming.logInputFormatDiagnostics(
            mimeType = trackInfo.mimeType,
            trackFormat = trackFormat,
            batchingEnabled = batchingEnabled,
        )

        Mp3ImportTiming.startStage("codec_creation")
        codec = createDecoder(trackInfo.mimeType)
        Mp3ImportTiming.stopStage("codec_creation")
        if (codec == null) {
            Mp3ImportTiming.recordFailure(stage = "codec_creation", error = null, partialWavDeleted = false)
            return decoderInitFailure(trackInfo.mimeType)
        }
        Mp3ImportTiming.setDecoderName(codec!!.name)

        Mp3ImportTiming.startStage("codec_configure_start")
        codec!!.configure(trackFormat, null, null, 0)
        codec!!.start()
        Mp3ImportTiming.stopStage("codec_configure_start")

        Mp3ImportTiming.startStage("decode_loop")
        val totalOutputFrames =
            runDecodeSession(
                trackInfo = trackInfo,
                codec = codec!!,
                extractor = extractor!!,
                batchingEnabled = batchingEnabled,
                trackFormat = trackFormat,
                resampler = createResamplerIfNeeded(trackInfo),
            )
        Mp3ImportTiming.stopStage("decode_loop")
        if (totalOutputFrames <= 0L) {
            Mp3ImportTiming.recordFailure(stage = "decode_loop", error = null, partialWavDeleted = false)
            return AudioImportResult.Failure.CorruptedMedia
        }

        return buildSuccess(trackInfo.channelCount, totalOutputFrames)
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
        codec = null
        extractor = null
    }

    private fun createResamplerIfNeeded(trackInfo: CompressedTrackInfo): LinearPcmResampler? =
        if (trackInfo.sampleRate == target.sampleRate) {
            null
        } else {
            LinearPcmResampler(
                sourceRate = trackInfo.sampleRate,
                targetRate = target.sampleRate,
                channelCount = trackInfo.channelCount,
            )
        }

    private fun attachExtractorDataSource(mediaExtractor: MediaExtractor): Boolean =
        try {
            mediaExtractor.setDataSource(context, source.uri, null)
            true
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun createDecoder(mimeType: String): MediaCodec? =
        try {
            MediaCodec.createDecoderByType(mimeType)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun decoderInitFailure(mimeType: String): AudioImportResult.Failure =
        if (mimeType.startsWith("audio/")) {
            AudioImportResult.Failure.DecoderInitFailed
        } else {
            AudioImportResult.Failure.UnsupportedCodec
        }

    private fun runDecodeSession(
        trackInfo: CompressedTrackInfo,
        codec: MediaCodec,
        extractor: MediaExtractor,
        batchingEnabled: Boolean,
        trackFormat: MediaFormat,
        resampler: LinearPcmResampler?,
    ): Long {
        val inputFeeder =
            MediaCodecCompressedInputFeeder(
                extractor = extractor,
                batchingEnabled = batchingEnabled,
                maxSampleReadBytes = CompressedMediaTrack.resolveMaxSampleReadBytes(trackFormat),
            )
        var totalOutputFrames = 0L
        StreamingPcmWavWriter(
            file = destinationFile,
            sampleRate = target.sampleRate,
            channelCount = trackInfo.channelCount,
            bitsPerSample = TARGET_BIT_DEPTH,
        ).use { writer ->
            MediaCodecDecodeSession(
                codec = codec,
                resampler = resampler,
                writer = writer,
                channelCount = trackInfo.channelCount,
                inputFeeder = inputFeeder,
                onDecodedFrames = { outputFrames ->
                    totalOutputFrames = outputFrames
                    emitProgressThrottled(
                        decodedDurationMs = (outputFrames * MS_PER_SECOND) / target.sampleRate,
                        estimatedDurationMs = estimatedDurationMs,
                    )
                },
            ).run(
                initialPcmEncoding = AudioFormat.ENCODING_PCM_16BIT,
                onOutputFormatChanged = { format ->
                    Mp3ImportTiming.setOutputPcmEncoding(CompressedMediaTrack.readPcmEncoding(format))
                },
            )
        }
        emitProgressThrottled(
            decodedDurationMs = (totalOutputFrames * MS_PER_SECOND) / target.sampleRate,
            estimatedDurationMs = estimatedDurationMs,
            force = true,
        )
        return totalOutputFrames
    }

    private fun emitProgressThrottled(
        decodedDurationMs: Long,
        estimatedDurationMs: Long,
        force: Boolean = false,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastProgressEmitElapsedMs < PROGRESS_EMIT_INTERVAL_MS) {
            return
        }
        lastProgressEmitElapsedMs = now
        val fraction =
            if (estimatedDurationMs > 0L) {
                (decodedDurationMs.toFloat() / estimatedDurationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        val progressStartMs = SystemClock.elapsedRealtime()
        onProgress(
            AudioImportProgressUpdate(
                fraction = fraction,
                decodedDurationMs = decodedDurationMs,
            ),
        )
        Mp3ImportTiming.recordProgressCallback()
        Mp3ImportTiming.addStage("progress_callback", SystemClock.elapsedRealtime() - progressStartMs)
    }

    private fun buildSuccess(channelCount: Int, totalOutputFrames: Long): AudioImportResult.Success {
        val durationMs = (totalOutputFrames * MS_PER_SECOND) / target.sampleRate
        return AudioImportResult.Success(
            durationMs = durationMs,
            channelMode = if (channelCount == 1) ChannelMode.MONO else ChannelMode.STEREO,
            channelCount = channelCount,
        )
    }

    private companion object {
        const val TARGET_BIT_DEPTH = 16
        const val MS_PER_SECOND = 1_000L
        const val PROGRESS_EMIT_INTERVAL_MS = 250L
    }
}
