package com.georgv.audioworkstation.core.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteBuffer

/**
 * MediaCodec decode/output loop: feeds compressed input, drains PCM output, writes WAV.
 * Waveform generation is intentionally out of scope (runs post-READY on the finalized WAV).
 */
internal class MediaCodecDecodeSession(
    private val codec: MediaCodec,
    private val resampler: LinearPcmResampler?,
    private val writer: StreamingPcmWavWriter,
    private val channelCount: Int,
    private val inputFeeder: MediaCodecCompressedInputFeeder,
    private val onDecodedFrames: (Long) -> Unit,
) {
    private val inputTimeoutUs = 10_000L
    private val outputTimeoutUs = 10_000L
    private val outputBufferInfo = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var totalOutputFrames = 0L
    private var framesSinceProgressEmit = 0L

    fun run(
        initialPcmEncoding: Int,
        onOutputFormatChanged: (MediaFormat) -> Unit,
    ): Long {
        var pcmEncoding = initialPcmEncoding
        while (!outputDone) {
            if (!inputDone) {
                feedAvailableInput()
            }
            val reachedEnd =
                drainAvailableOutput(
                    pcmEncoding = pcmEncoding,
                    onOutputFormatChanged = { format ->
                        pcmEncoding = CompressedMediaTrack.readPcmEncoding(format)
                        onOutputFormatChanged(format)
                    },
                )
            if (reachedEnd) {
                outputDone = true
            }
        }
        emitProgressIfNeeded(force = true)
        Mp3ImportTiming.applyInputFeederStats(inputFeeder)
        Mp3ImportTiming.setDecodedFrameCount(totalOutputFrames)
        return totalOutputFrames
    }

    private fun feedAvailableInput() {
        while (!inputDone) {
            if (feedInput()) {
                break
            }
        }
    }

    private fun feedInput(): Boolean {
        val feedStartMs = SystemClock.elapsedRealtime()
        try {
            val dequeueStartMs = SystemClock.elapsedRealtime()
            val inputBufferIndex = codec.dequeueInputBuffer(inputTimeoutUs)
            val dequeueMs = SystemClock.elapsedRealtime() - dequeueStartMs
            Mp3ImportTiming.addStage("input_dequeue_wait", dequeueMs)
            if (inputBufferIndex < 0) {
                Mp3ImportTiming.recordInputTryAgainLater()
                return true
            }

            val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return true
            inputFeeder.recordCodecInputCapacity(inputBuffer.capacity())

            val readStartMs = SystemClock.elapsedRealtime()
            val fillResult = inputFeeder.fillInputBuffer(inputBuffer)
            Mp3ImportTiming.addStage("extractor_read_sample_data", SystemClock.elapsedRealtime() - readStartMs)

            when (fillResult) {
                MediaCodecCompressedInputFeeder.InputFillResult.EndOfStream -> {
                    queueInputBuffer(
                        inputBufferIndex = inputBufferIndex,
                        offset = 0,
                        sizeBytes = 0,
                        presentationTimeUs = 0L,
                        flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        compressedBytes = 0,
                        samplesInBatch = 0,
                    )
                    inputDone = true
                    return true
                }
                is MediaCodecCompressedInputFeeder.InputFillResult.Queued -> {
                    queueInputBuffer(
                        inputBufferIndex = inputBufferIndex,
                        offset = 0,
                        sizeBytes = fillResult.sizeBytes,
                        presentationTimeUs = fillResult.presentationTimeUs,
                        flags = 0,
                        compressedBytes = fillResult.sizeBytes,
                        samplesInBatch = fillResult.samplesInBatch,
                    )
                    return false
                }
            }
        } finally {
            Mp3ImportTiming.addStage("input_feeding_total", SystemClock.elapsedRealtime() - feedStartMs)
        }
    }

    private fun queueInputBuffer(
        inputBufferIndex: Int,
        offset: Int,
        sizeBytes: Int,
        presentationTimeUs: Long,
        flags: Int,
        compressedBytes: Int,
        samplesInBatch: Int,
    ) {
        val queueStartMs = SystemClock.elapsedRealtime()
        codec.queueInputBuffer(inputBufferIndex, offset, sizeBytes, presentationTimeUs, flags)
        Mp3ImportTiming.addStage("codec_queue_input_buffer", SystemClock.elapsedRealtime() - queueStartMs)
        inputFeeder.recordInputBufferQueued(
            sizeBytes = compressedBytes,
            samplesInBatch = samplesInBatch,
        )
    }

    private fun drainAvailableOutput(
        pcmEncoding: Int,
        onOutputFormatChanged: (MediaFormat) -> Unit,
    ): Boolean {
        var endOfStream = false
        while (true) {
            when (
                val result =
                    drainOutput(
                        pcmEncoding = pcmEncoding,
                        onOutputFormatChanged = onOutputFormatChanged,
                    )
            ) {
                DrainResult.Continue -> continue
                DrainResult.TryAgain -> return endOfStream
                DrainResult.EndOfStream -> return true
            }
        }
    }

    private fun drainOutput(
        pcmEncoding: Int,
        onOutputFormatChanged: (MediaFormat) -> Unit,
    ): DrainResult {
        val dequeueStartMs = SystemClock.elapsedRealtime()
        val outputBufferIndex = codec.dequeueOutputBuffer(outputBufferInfo, outputTimeoutUs)
        Mp3ImportTiming.addStage("output_dequeue_wait", SystemClock.elapsedRealtime() - dequeueStartMs)
        when {
            outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                Mp3ImportTiming.recordOutputTryAgainLater()
                return DrainResult.TryAgain
            }
            outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                onOutputFormatChanged(codec.outputFormat)
                return DrainResult.Continue
            }
            outputBufferIndex >= 0 -> {
                Mp3ImportTiming.recordOutputBuffer(outputBufferInfo.size)
                var framesDecoded = 0L
                if (outputBufferInfo.size > 0) {
                    val holdStartMs = SystemClock.elapsedRealtime()
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null) {
                        framesDecoded =
                            writeDecodedPcm(
                                outputBuffer = outputBuffer,
                                offset = outputBufferInfo.offset,
                                sizeBytes = outputBufferInfo.size,
                                pcmEncoding = pcmEncoding,
                            )
                    }
                    Mp3ImportTiming.addStage(
                        "output_buffer_hold",
                        SystemClock.elapsedRealtime() - holdStartMs,
                    )
                }
                val endOfStream =
                    (outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                codec.releaseOutputBuffer(outputBufferIndex, false)
                if (framesDecoded > 0L) {
                    totalOutputFrames += framesDecoded
                    framesSinceProgressEmit += framesDecoded
                    emitProgressIfNeeded(force = false)
                }
                return if (endOfStream) DrainResult.EndOfStream else DrainResult.Continue
            }
            else -> {
                Mp3ImportTiming.recordOutputTryAgainLater()
                return DrainResult.TryAgain
            }
        }
    }

    private fun writeDecodedPcm(
        outputBuffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        pcmEncoding: Int,
    ): Long {
        if (sizeBytes <= 0) return 0L
        if (usePcm16FastPath(pcmEncoding)) {
            val fastPathStartMs = SystemClock.elapsedRealtime()
            val writeStartMs = SystemClock.elapsedRealtime()
            writer.writePcm16FromByteBuffer(
                buffer = outputBuffer,
                offset = offset,
                sizeBytes = sizeBytes,
            )
            Mp3ImportTiming.addStage("wav_write", SystemClock.elapsedRealtime() - writeStartMs)
            Mp3ImportTiming.addStage("pcm16_fast_path", SystemClock.elapsedRealtime() - fastPathStartMs)
            Mp3ImportTiming.addPcmBytesWritten(sizeBytes.toLong())
            return (sizeBytes / (BYTES_PER_SAMPLE * channelCount)).toLong()
        }

        val convertStartMs = SystemClock.elapsedRealtime()
        val decoded =
            PcmFormatConverter.decodeOutputBuffer(
                buffer = outputBuffer,
                offset = offset,
                sizeBytes = sizeBytes,
                encoding = pcmEncoding,
                channelCount = channelCount,
            )
        Mp3ImportTiming.addStage("pcm_convert", SystemClock.elapsedRealtime() - convertStartMs)
        if (decoded.isEmpty()) return 0L

        val inputFrameCount = decoded.size / channelCount
        val resampleStartMs = SystemClock.elapsedRealtime()
        val resampled =
            if (resampler == null) {
                decoded
            } else {
                resampler.resample(decoded, inputFrameCount)
            }
        Mp3ImportTiming.addStage("resample", SystemClock.elapsedRealtime() - resampleStartMs)
        if (resampled.isEmpty()) return 0L

        val writeStartMs = SystemClock.elapsedRealtime()
        writer.writePcmInt16(resampled, resampled.size)
        Mp3ImportTiming.addStage("wav_write", SystemClock.elapsedRealtime() - writeStartMs)
        Mp3ImportTiming.addPcmBytesWritten(resampled.size.toLong() * BYTES_PER_SAMPLE)

        return (resampled.size / channelCount).toLong()
    }

    private fun usePcm16FastPath(pcmEncoding: Int): Boolean =
        resampler == null && pcmEncoding == AudioFormat.ENCODING_PCM_16BIT

    private fun emitProgressIfNeeded(force: Boolean) {
        if (!force && framesSinceProgressEmit < FRAMES_BETWEEN_PROGRESS) {
            return
        }
        framesSinceProgressEmit = 0L
        onDecodedFrames(totalOutputFrames)
    }

    private enum class DrainResult {
        Continue,
        TryAgain,
        EndOfStream,
    }

    private companion object {
        const val FRAMES_BETWEEN_PROGRESS = 4_096L
        const val BYTES_PER_SAMPLE = 2
    }
}
