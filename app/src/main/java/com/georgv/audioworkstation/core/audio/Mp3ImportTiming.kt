package com.georgv.audioworkstation.core.audio

import android.media.AudioFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Wall-clock diagnostics for compressed-audio import. Filter logcat with [TAG].
 *
 * Session ends at READY (decode complete). Post-READY exact waveform extraction is logged
 * separately via [logPostReadyWaveformExtract].
 */
internal open class Mp3ImportTimingCore {
    companion object {
        const val TAG = "MP3_IMPORT_TIMING"

        private val SUMMARY_STAGE_KEYS =
            listOf(
                "metadata_read",
                "db_upsert_importing",
                "extractor_setup",
                "track_selection",
                "codec_creation",
                "codec_configure_start",
                "decode_loop",
                "input_feeding_total",
                "input_dequeue_wait",
                "extractor_read_sample_data",
                "codec_queue_input_buffer",
                "output_dequeue_wait",
                "pcm_convert",
                "pcm16_fast_path",
                "output_buffer_hold",
                "resample",
                "wav_write",
                "progress_callback",
                "wav_header_patch",
                "db_ready_update",
            )
    }

    private val lock = Any()

    private var sessionActive = false
    private var sessionLabel = ""
    private var sessionStartMs = 0L

    private val stageMs = mutableMapOf<String, Long>()
    private val stageStartMs = mutableMapOf<String, Long>()

    private var sourceDisplayName: String? = null
    private var sourceUriScheme: String? = null
    private var mimeType: String? = null
    private var sourceSampleRate = 0
    private var targetSampleRate = 0
    private var resamplingEnabled = false
    private var mismatchDetected = false
    private var sampleRateMismatchUserChoice: String? = null
    private var newProjectSampleRate: Int? = null
    private var channelCount = 0
    private var durationMs = 0L
    private var estimatedFrameCount = 0L
    private var decoderName: String? = null
    private var outputPcmEncoding: Int? = null

    private var inputBuffersQueued = 0
    private var outputBuffersDrained = 0
    private var inputTryAgainLaterCount = 0
    private var outputTryAgainLaterCount = 0
    private var compressedBytesRead = 0L
    private var pcmBytesWritten = 0L
    private var decodedFrameCount = 0L
    private var progressCallbackCount = 0
    private var outputBufferSizeSum = 0L
    private var outputBufferSizeCount = 0
    private var maxOutputBufferSize = 0

    private var inputBatchingEnabled = false
    private var formatMaxInputSize: Int? = null
    private var inputSamplesRead = 0
    private var inputSamplesInBatchSum = 0
    private var inputSamplesInBatchCount = 0
    private var maxInputBytesPerBuffer = 0
    private var extractorSampleSizeSum = 0L
    private var extractorSampleSizeCount = 0
    private var minExtractorSampleSize = Int.MAX_VALUE
    private var maxExtractorSampleSize = 0
    private var codecInputCapacityMin = Int.MAX_VALUE
    private var codecInputCapacityMax = 0
    private var codecInputCapacitySum = 0L
    private var codecInputCapacityCount = 0
    private var safeMaxBatchBytes = CompressedImportDecodeConfig.SAFE_MAX_BATCH_BYTES
    private var effectiveBatchMaxBytesMin = Int.MAX_VALUE
    private var effectiveBatchMaxBytesMax = 0
    private var effectiveBatchMaxBytesSum = 0L
    private var effectiveBatchMaxBytesCount = 0
    private var inputBufferFillRatioSum = 0.0
    private var inputBufferFillRatioCount = 0
    private var maxInputBufferFillRatio = 0.0
    private var maxSamplesPerInputBuffer = 0
    private var nearFullInputBuffers = 0
    private var underfilledInputBuffers = 0
    private var underfilledEndOfStream = 0
    private var underfilledNextSampleWouldNotFit = 0
    private var underfilledNonMonotonicTimestamp = 0
    private var underfilledSafetyCapReached = 0
    private var underfilledSingleSampleMode = 0
    private var timestampsMonotonic = true
    private var inputDiagnosticsLogged = false

    private var failureStage: String? = null
    private var failureMessage: String? = null
    private var partialWavDeleted = false
    private var metadataLogged = false

    fun beginSession(label: String) {
        synchronized(lock) {
            resetLocked()
            sessionActive = true
            sessionLabel = label
            sessionStartMs = SystemClock.elapsedRealtime()
        }
        Log.i(TAG, "session_begin label=$label device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT}")
    }

    fun setMetadata(
        sourceDisplayName: String?,
        sourceUriScheme: String?,
        mimeType: String?,
        sourceSampleRate: Int,
        targetSampleRate: Int,
        resamplingEnabled: Boolean,
        mismatchDetected: Boolean = resamplingEnabled,
        channelCount: Int,
        durationMs: Long,
        estimatedFrameCount: Long,
    ) {
        synchronized(lock) {
            this.sourceDisplayName = sourceDisplayName
            this.sourceUriScheme = sourceUriScheme
            this.mimeType = mimeType
            this.sourceSampleRate = sourceSampleRate
            this.targetSampleRate = targetSampleRate
            this.resamplingEnabled = resamplingEnabled
            this.mismatchDetected = mismatchDetected
            this.channelCount = channelCount
            this.durationMs = durationMs
            this.estimatedFrameCount = estimatedFrameCount
        }
        logMetadataOnce()
    }

    fun recordSampleRateMismatchChoice(
        choice: String,
        newProjectSampleRateHz: Int? = null,
    ) {
        synchronized(lock) {
            if (!sessionActive) return
            sampleRateMismatchUserChoice = choice
            newProjectSampleRate = newProjectSampleRateHz
        }
        Log.i(
            TAG,
            "sample_rate_mismatch_choice userChoice=$choice " +
                "newProjectSampleRate=${newProjectSampleRateHz ?: "n/a"}",
        )
    }

    fun setDecoderName(name: String?) {
        synchronized(lock) {
            decoderName = name
        }
    }

    fun setOutputPcmEncoding(encoding: Int) {
        synchronized(lock) {
            outputPcmEncoding = encoding
        }
    }

    fun setInputBatchingEnabled(enabled: Boolean) {
        synchronized(lock) {
            inputBatchingEnabled = enabled
        }
    }

    fun logInputFormatDiagnostics(
        mimeType: String,
        trackFormat: android.media.MediaFormat,
        batchingEnabled: Boolean,
    ) {
        val maxInputSize =
            if (trackFormat.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) {
                trackFormat.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                null
            }
        synchronized(lock) {
            if (!sessionActive || inputDiagnosticsLogged) return
            inputDiagnosticsLogged = true
            formatMaxInputSize = maxInputSize
        }
        Log.i(
            TAG,
            "input_diagnostics " +
                "mime=$mimeType " +
                "batchingEnabled=$batchingEnabled " +
                "formatMaxInputSize=${maxInputSize ?: "absent"} " +
                "safeMaxBatchBytes=${CompressedImportDecodeConfig.safeMaxBatchBytes()} " +
                "debugSafeMaxBatchBytesOverride=${CompressedImportDecodeConfig.debugSafeMaxBatchBytesOverride} " +
                "debugUseCodecCapacityOnly=${CompressedImportDecodeConfig.DEBUG_USE_CODEC_CAPACITY_ONLY} " +
                "batchingSafeForMime=${CompressedImportDecodeConfig.batchingEnabledFor(mimeType)}",
        )
    }

    fun applyInputFeederStats(feeder: MediaCodecCompressedInputFeeder) {
        synchronized(lock) {
            if (!sessionActive) return
            inputBuffersQueued = feeder.inputBuffersQueued
            compressedBytesRead = feeder.inputBytesSum
            inputSamplesRead = feeder.inputSamplesRead
            extractorSampleSizeSum = feeder.extractorSampleSizeSum
            extractorSampleSizeCount = feeder.extractorSampleSizeCount
            minExtractorSampleSize = feeder.minExtractorSampleSize
            maxExtractorSampleSize = feeder.maxExtractorSampleSize
            maxInputBytesPerBuffer = feeder.maxInputBytesPerBuffer
            inputSamplesInBatchSum = feeder.samplesPerInputBufferSum
            inputSamplesInBatchCount = feeder.samplesPerInputBufferCount
            codecInputCapacityMin = feeder.codecInputCapacityMin
            codecInputCapacityMax = feeder.codecInputCapacityMax
            codecInputCapacitySum = feeder.codecInputCapacitySum
            codecInputCapacityCount = feeder.codecInputCapacityCount
            safeMaxBatchBytes = feeder.safeMaxBatchBytes
            effectiveBatchMaxBytesMin = feeder.effectiveBatchMaxBytesMin
            effectiveBatchMaxBytesMax = feeder.effectiveBatchMaxBytesMax
            effectiveBatchMaxBytesSum = feeder.effectiveBatchMaxBytesSum
            effectiveBatchMaxBytesCount = feeder.effectiveBatchMaxBytesCount
            inputBufferFillRatioSum = feeder.inputBufferFillRatioSum
            inputBufferFillRatioCount = feeder.inputBufferFillRatioCount
            maxInputBufferFillRatio = feeder.maxInputBufferFillRatio
            maxSamplesPerInputBuffer = feeder.maxSamplesPerInputBuffer
            nearFullInputBuffers = feeder.nearFullInputBuffers
            underfilledInputBuffers = feeder.underfilledInputBuffers
            underfilledEndOfStream = feeder.underfilledEndOfStream
            underfilledNextSampleWouldNotFit = feeder.underfilledNextSampleWouldNotFit
            underfilledNonMonotonicTimestamp = feeder.underfilledNonMonotonicTimestamp
            underfilledSafetyCapReached = feeder.underfilledSafetyCapReached
            underfilledSingleSampleMode = feeder.underfilledSingleSampleMode
            timestampsMonotonic = feeder.timestampsMonotonic
        }
    }

    fun startStage(stage: String) {
        synchronized(lock) {
            if (!sessionActive) return
            stageStartMs[stage] = SystemClock.elapsedRealtime()
        }
    }

    fun stopStage(stage: String) {
        synchronized(lock) {
            if (!sessionActive) return
            val started = stageStartMs.remove(stage) ?: return
            addStageLocked(stage, SystemClock.elapsedRealtime() - started)
        }
    }

    fun addStage(stage: String, durationMs: Long) {
        if (durationMs <= 0L) return
        synchronized(lock) {
            if (!sessionActive) return
            addStageLocked(stage, durationMs)
        }
    }

    inline fun runStage(stage: String, block: () -> Unit) {
        if (!isSessionActive()) {
            block()
            return
        }
        val startMs = SystemClock.elapsedRealtime()
        block()
        addStage(stage, SystemClock.elapsedRealtime() - startMs)
    }

    fun isSessionActive(): Boolean =
        synchronized(lock) {
            sessionActive
        }

    fun recordInputTryAgainLater() {
        synchronized(lock) {
            if (!sessionActive) return
            inputTryAgainLaterCount++
        }
    }

    fun recordOutputBuffer(sizeBytes: Int) {
        synchronized(lock) {
            if (!sessionActive) return
            outputBuffersDrained++
            if (sizeBytes <= 0) return
            outputBufferSizeSum += sizeBytes.toLong()
            outputBufferSizeCount++
            if (sizeBytes > maxOutputBufferSize) {
                maxOutputBufferSize = sizeBytes
            }
        }
    }

    fun recordOutputTryAgainLater() {
        synchronized(lock) {
            if (!sessionActive) return
            outputTryAgainLaterCount++
        }
    }

    fun addPcmBytesWritten(byteCount: Long) {
        if (byteCount <= 0L) return
        synchronized(lock) {
            if (!sessionActive) return
            pcmBytesWritten += byteCount
        }
    }

    fun setDecodedFrameCount(frames: Long) {
        synchronized(lock) {
            if (!sessionActive) return
            decodedFrameCount = frames
        }
    }

    fun recordProgressCallback() {
        synchronized(lock) {
            if (!sessionActive) return
            progressCallbackCount++
        }
    }

    fun logPostReadyWaveformExtract(trackId: String, durationMs: Long, success: Boolean) {
        if (durationMs <= 0L) return
        Log.i(
            TAG,
            "post_ready_waveform_extract track=$trackId duration_ms=$durationMs success=$success",
        )
    }

    suspend fun <T> measureWallClock(block: suspend () -> T): Pair<T, Long> {
        if (!isWallClockAvailable()) {
            return block() to 0L
        }
        val startMs = SystemClock.elapsedRealtime()
        val result = block()
        return result to (SystemClock.elapsedRealtime() - startMs)
    }

    private fun isWallClockAvailable(): Boolean =
        runCatching {
            SystemClock.elapsedRealtime()
            true
        }.getOrDefault(false)

    fun recordFailure(stage: String, error: Throwable?, partialWavDeleted: Boolean) {
        synchronized(lock) {
            failureStage = stage
            failureMessage = error?.let { "${it.javaClass.simpleName}: ${it.message}" }
            this.partialWavDeleted = partialWavDeleted
        }
    }

    fun endSession(outcome: String) {
        val snapshot = synchronized(lock) {
            if (!sessionActive) return
            buildSnapshot(outcome)
        }
        logFailureIfNeeded(snapshot)
        logSummary(snapshot)
        synchronized(lock) {
            resetLocked()
        }
    }

    private fun logMetadataOnce() {
        val shouldLog =
            synchronized(lock) {
                if (!sessionActive || metadataLogged) return
                metadataLogged = true
                true
            }
        if (!shouldLog) return
        synchronized(lock) {
            Log.i(
                TAG,
                "metadata " +
                    "displayName=${sourceDisplayName ?: "unknown"} " +
                    "uriScheme=${sourceUriScheme ?: "unknown"} " +
                    "mime=${mimeType ?: "unknown"} " +
                    "sourceRate=$sourceSampleRate " +
                    "targetRate=$targetSampleRate " +
                    "mismatchDetected=$mismatchDetected " +
                    "resamplingEnabled=$resamplingEnabled " +
                    "channels=$channelCount " +
                    "durationMs=$durationMs " +
                    "estimatedFrames=$estimatedFrameCount " +
                    "decoder=${decoderName ?: "pending"} " +
                    "pcmEncoding=${outputPcmEncoding?.let(::pcmEncodingLabel) ?: "pending"}",
            )
        }
    }

    private fun logFailureIfNeeded(snapshot: SessionSnapshot) {
        if (snapshot.failureStage == null) return
        Log.w(
            TAG,
            "FAILURE stage=${snapshot.failureStage} " +
                "elapsed_ms=${snapshot.totalMs} " +
                "exception=${snapshot.failureMessage ?: "none"} " +
                "partialWavDeleted=${snapshot.partialWavDeleted}",
        )
    }

    private fun logSummary(snapshot: SessionSnapshot) {
        Log.i(TAG, "SUMMARY outcome=${snapshot.outcome}")
        Log.i(TAG, "SUMMARY total_ms=${snapshot.totalMs}")
        SUMMARY_STAGE_KEYS.forEach { stage ->
            Log.i(TAG, "SUMMARY ${stage}_ms=${snapshot.stageMs[stage] ?: 0}")
        }
        logSummaryCompact(snapshot)
        logSummaryCounters(snapshot)
        logSummaryInput(snapshot)
        logSampleRateMismatchChoice(snapshot)
    }

    private fun logSampleRateMismatchChoice(snapshot: SessionSnapshot) {
        if (!snapshot.mismatchDetected && snapshot.sampleRateMismatchUserChoice == null) return
        Log.i(
            TAG,
            "SUMMARY sample_rate_mismatch " +
                "mismatchDetected=${snapshot.mismatchDetected} " +
                "userChoice=${snapshot.sampleRateMismatchUserChoice ?: "pending"} " +
                "newProjectSampleRate=${snapshot.newProjectSampleRate ?: "n/a"}",
        )
    }

    private fun logSummaryCompact(snapshot: SessionSnapshot) {
        Log.i(
            TAG,
            "SUMMARY compact " +
                "totalUntilReady=${snapshot.totalMs} " +
                "decodeLoop=${snapshot.stageMs["decode_loop"] ?: 0} " +
                "inputWait=${snapshot.stageMs["input_dequeue_wait"] ?: 0} " +
                "outputWait=${snapshot.stageMs["output_dequeue_wait"] ?: 0} " +
                "pcmConvert=${snapshot.stageMs["pcm_convert"] ?: 0} " +
                "pcm16FastPath=${snapshot.stageMs["pcm16_fast_path"] ?: 0} " +
                "outputBufferHold=${snapshot.stageMs["output_buffer_hold"] ?: 0} " +
                "resample=${snapshot.stageMs["resample"] ?: 0} " +
                "wavWrite=${snapshot.stageMs["wav_write"] ?: 0} " +
                "progressCallbacks=${snapshot.progressCallbackCount} " +
                "dbReady=${snapshot.stageMs["db_ready_update"] ?: 0} " +
                "resamplingEnabled=${snapshot.resamplingEnabled} " +
                "decoder=${snapshot.decoderName ?: "unknown"} " +
                "pcmEncoding=${snapshot.outputPcmEncoding?.let(::pcmEncodingLabel) ?: "unknown"} " +
                "inputBuffers=${snapshot.inputBuffersQueued} " +
                "inputBatching=${snapshot.inputBatchingEnabled} " +
                "avgSamplesPerInputBuffer=${snapshot.avgSamplesPerInputBuffer} " +
                "outputBuffers=${snapshot.outputBuffersDrained} " +
                "compressedBytes=${snapshot.compressedBytesRead} " +
                "pcmBytes=${snapshot.pcmBytesWritten} " +
                "frames=${snapshot.decodedFrameCount}",
        )
    }

    private fun logSummaryCounters(snapshot: SessionSnapshot) {
        Log.i(
            TAG,
            "SUMMARY counters " +
                "inputTryAgainLater=${snapshot.inputTryAgainLaterCount} " +
                "outputTryAgainLater=${snapshot.outputTryAgainLaterCount} " +
                "progressCallbackCount=${snapshot.progressCallbackCount} " +
                "avgOutputBufferBytes=${snapshot.avgOutputBufferSize} " +
                "maxOutputBufferBytes=${snapshot.maxOutputBufferSize}",
        )
    }

    private fun logSummaryInput(snapshot: SessionSnapshot) {
        Log.i(
            TAG,
            "SUMMARY input " +
                "inputBatchingEnabled=${snapshot.inputBatchingEnabled} " +
                "formatMaxInputSize=${snapshot.formatMaxInputSize ?: "absent"} " +
                "safeMaxBatchBytes=${snapshot.safeMaxBatchBytes} " +
                "effectiveBatchMaxBytesMin=${snapshot.effectiveBatchMaxBytesMin} " +
                "effectiveBatchMaxBytesAvg=${snapshot.effectiveBatchMaxBytesAvg} " +
                "effectiveBatchMaxBytesMax=${snapshot.effectiveBatchMaxBytesMax} " +
                "inputSamplesRead=${snapshot.inputSamplesRead} " +
                "inputBuffersQueued=${snapshot.inputBuffersQueued} " +
                "avgSamplesPerInputBuffer=${snapshot.avgSamplesPerInputBuffer} " +
                "maxSamplesPerInputBuffer=${snapshot.maxSamplesPerInputBuffer} " +
                "avgInputBytesPerBuffer=${snapshot.avgInputBytesPerBuffer} " +
                "maxInputBytesPerBuffer=${snapshot.maxInputBytesPerBuffer} " +
                "inputBufferFillRatioAvg=${snapshot.inputBufferFillRatioAvg} " +
                "inputBufferFillRatioMax=${snapshot.inputBufferFillRatioMaxFormatted} " +
                "nearFullInputBuffers=${snapshot.nearFullInputBuffers} " +
                "underfilledInputBuffers=${snapshot.underfilledInputBuffers} " +
                "underfilledEndOfStream=${snapshot.underfilledEndOfStream} " +
                "underfilledNextSampleWouldNotFit=${snapshot.underfilledNextSampleWouldNotFit} " +
                "underfilledNonMonotonicTimestamp=${snapshot.underfilledNonMonotonicTimestamp} " +
                "underfilledSafetyCapReached=${snapshot.underfilledSafetyCapReached} " +
                "underfilledSingleSampleMode=${snapshot.underfilledSingleSampleMode} " +
                "avgExtractorSampleBytes=${snapshot.avgExtractorSampleSize} " +
                "minExtractorSampleBytes=${snapshot.minExtractorSampleSize} " +
                "maxExtractorSampleBytes=${snapshot.maxExtractorSampleSize} " +
                "codecInputCapacityMin=${snapshot.codecInputCapacityMin} " +
                "codecInputCapacityAvg=${snapshot.codecInputCapacityAvg} " +
                "codecInputCapacityMax=${snapshot.codecInputCapacityMax} " +
                "timestampsMonotonic=${snapshot.timestampsMonotonic}",
        )
    }

    private fun buildSnapshot(outcome: String): SessionSnapshot {
        val totalMs =
            if (sessionStartMs > 0L) {
                SystemClock.elapsedRealtime() - sessionStartMs
            } else {
                0L
            }
        return SessionSnapshot(
            outcome = outcome,
            sessionLabel = sessionLabel,
            totalMs = totalMs,
            stageMs = stageMs.toMap(),
            sourceDisplayName = sourceDisplayName,
            sourceUriScheme = sourceUriScheme,
            mimeType = mimeType,
            sourceSampleRate = sourceSampleRate,
            targetSampleRate = targetSampleRate,
            resamplingEnabled = resamplingEnabled,
            mismatchDetected = mismatchDetected,
            sampleRateMismatchUserChoice = sampleRateMismatchUserChoice,
            newProjectSampleRate = newProjectSampleRate,
            channelCount = channelCount,
            durationMs = durationMs,
            estimatedFrameCount = estimatedFrameCount,
            decoderName = decoderName,
            outputPcmEncoding = outputPcmEncoding,
            inputBuffersQueued = inputBuffersQueued,
            outputBuffersDrained = outputBuffersDrained,
            inputTryAgainLaterCount = inputTryAgainLaterCount,
            outputTryAgainLaterCount = outputTryAgainLaterCount,
            compressedBytesRead = compressedBytesRead,
            pcmBytesWritten = pcmBytesWritten,
            decodedFrameCount = decodedFrameCount,
            progressCallbackCount = progressCallbackCount,
            avgOutputBufferSize = averageOrZero(outputBufferSizeSum, outputBufferSizeCount),
            maxOutputBufferSize = maxOutputBufferSize,
            inputBatchingEnabled = inputBatchingEnabled,
            formatMaxInputSize = formatMaxInputSize,
            inputSamplesRead = inputSamplesRead,
            avgSamplesPerInputBuffer = averageOrZero(inputSamplesInBatchSum, inputSamplesInBatchCount),
            avgInputBytesPerBuffer = averageOrZero(compressedBytesRead, inputBuffersQueued),
            maxInputBytesPerBuffer = maxInputBytesPerBuffer,
            avgExtractorSampleSize = averageOrZero(extractorSampleSizeSum, extractorSampleSizeCount),
            minExtractorSampleSize = if (extractorSampleSizeCount > 0) minExtractorSampleSize else 0,
            maxExtractorSampleSize = maxExtractorSampleSize,
            codecInputCapacityMin = if (codecInputCapacityCount > 0) codecInputCapacityMin else 0,
            codecInputCapacityAvg = averageOrZero(codecInputCapacitySum, codecInputCapacityCount),
            codecInputCapacityMax = codecInputCapacityMax,
            safeMaxBatchBytes = safeMaxBatchBytes,
            effectiveBatchMaxBytesMin = if (effectiveBatchMaxBytesCount > 0) effectiveBatchMaxBytesMin else 0,
            effectiveBatchMaxBytesAvg = averageOrZero(effectiveBatchMaxBytesSum, effectiveBatchMaxBytesCount),
            effectiveBatchMaxBytesMax = effectiveBatchMaxBytesMax,
            maxSamplesPerInputBuffer = maxSamplesPerInputBuffer,
            inputBufferFillRatioAvg = averageFillRatioOrZero(inputBufferFillRatioSum, inputBufferFillRatioCount),
            inputBufferFillRatioMax = maxInputBufferFillRatio,
            inputBufferFillRatioMaxFormatted = formatFillRatio(maxInputBufferFillRatio),
            nearFullInputBuffers = nearFullInputBuffers,
            underfilledInputBuffers = underfilledInputBuffers,
            underfilledEndOfStream = underfilledEndOfStream,
            underfilledNextSampleWouldNotFit = underfilledNextSampleWouldNotFit,
            underfilledNonMonotonicTimestamp = underfilledNonMonotonicTimestamp,
            underfilledSafetyCapReached = underfilledSafetyCapReached,
            underfilledSingleSampleMode = underfilledSingleSampleMode,
            timestampsMonotonic = timestampsMonotonic,
            failureStage = failureStage,
            failureMessage = failureMessage,
            partialWavDeleted = partialWavDeleted,
        )
    }

    private fun averageOrZero(sum: Long, count: Int): Long = if (count > 0) sum / count else 0L

    private fun averageOrZero(sum: Int, count: Int): Int = if (count > 0) sum / count else 0

    private fun addStageLocked(stage: String, durationMs: Long) {
        stageMs[stage] = (stageMs[stage] ?: 0L) + durationMs
    }

    private fun resetLocked() {
        sessionActive = false
        sessionLabel = ""
        sessionStartMs = 0L
        stageMs.clear()
        stageStartMs.clear()
        sourceDisplayName = null
        sourceUriScheme = null
        mimeType = null
        sourceSampleRate = 0
        targetSampleRate = 0
        resamplingEnabled = false
        mismatchDetected = false
        sampleRateMismatchUserChoice = null
        newProjectSampleRate = null
        channelCount = 0
        durationMs = 0L
        estimatedFrameCount = 0L
        decoderName = null
        outputPcmEncoding = null
        inputBuffersQueued = 0
        outputBuffersDrained = 0
        inputTryAgainLaterCount = 0
        outputTryAgainLaterCount = 0
        compressedBytesRead = 0L
        pcmBytesWritten = 0L
        decodedFrameCount = 0L
        progressCallbackCount = 0
        outputBufferSizeSum = 0L
        outputBufferSizeCount = 0
        maxOutputBufferSize = 0
        inputBatchingEnabled = false
        formatMaxInputSize = null
        inputSamplesRead = 0
        inputSamplesInBatchSum = 0
        inputSamplesInBatchCount = 0
        maxInputBytesPerBuffer = 0
        extractorSampleSizeSum = 0L
        extractorSampleSizeCount = 0
        minExtractorSampleSize = Int.MAX_VALUE
        maxExtractorSampleSize = 0
        codecInputCapacityMin = Int.MAX_VALUE
        codecInputCapacityMax = 0
        codecInputCapacitySum = 0L
        codecInputCapacityCount = 0
        safeMaxBatchBytes = CompressedImportDecodeConfig.SAFE_MAX_BATCH_BYTES
        effectiveBatchMaxBytesMin = Int.MAX_VALUE
        effectiveBatchMaxBytesMax = 0
        effectiveBatchMaxBytesSum = 0L
        effectiveBatchMaxBytesCount = 0
        inputBufferFillRatioSum = 0.0
        inputBufferFillRatioCount = 0
        maxInputBufferFillRatio = 0.0
        maxSamplesPerInputBuffer = 0
        nearFullInputBuffers = 0
        underfilledInputBuffers = 0
        underfilledEndOfStream = 0
        underfilledNextSampleWouldNotFit = 0
        underfilledNonMonotonicTimestamp = 0
        underfilledSafetyCapReached = 0
        underfilledSingleSampleMode = 0
        timestampsMonotonic = true
        inputDiagnosticsLogged = false
        failureStage = null
        failureMessage = null
        partialWavDeleted = false
        metadataLogged = false
    }

    private fun pcmEncodingLabel(encoding: Int): String =
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> "pcm_16bit"
            AudioFormat.ENCODING_PCM_8BIT -> "pcm_8bit"
            AudioFormat.ENCODING_PCM_FLOAT -> "pcm_float"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "pcm_24bit_packed"
            AudioFormat.ENCODING_PCM_32BIT -> "pcm_32bit"
            else -> "pcm_$encoding"
        }

    private fun averageFillRatioOrZero(sum: Double, count: Int): String = formatFillRatio(if (count > 0) sum / count else 0.0)

    private fun formatFillRatio(ratio: Double): String = String.format(java.util.Locale.US, "%.3f", ratio)

    private data class SessionSnapshot(
        val outcome: String,
        val sessionLabel: String,
        val totalMs: Long,
        val stageMs: Map<String, Long>,
        val sourceDisplayName: String?,
        val sourceUriScheme: String?,
        val mimeType: String?,
        val sourceSampleRate: Int,
        val targetSampleRate: Int,
        val resamplingEnabled: Boolean,
        val mismatchDetected: Boolean,
        val sampleRateMismatchUserChoice: String?,
        val newProjectSampleRate: Int?,
        val channelCount: Int,
        val durationMs: Long,
        val estimatedFrameCount: Long,
        val decoderName: String?,
        val outputPcmEncoding: Int?,
        val inputBuffersQueued: Int,
        val outputBuffersDrained: Int,
        val inputTryAgainLaterCount: Int,
        val outputTryAgainLaterCount: Int,
        val compressedBytesRead: Long,
        val pcmBytesWritten: Long,
        val decodedFrameCount: Long,
        val progressCallbackCount: Int,
        val avgOutputBufferSize: Long,
        val maxOutputBufferSize: Int,
        val inputBatchingEnabled: Boolean,
        val formatMaxInputSize: Int?,
        val inputSamplesRead: Int,
        val avgSamplesPerInputBuffer: Int,
        val avgInputBytesPerBuffer: Long,
        val maxInputBytesPerBuffer: Int,
        val avgExtractorSampleSize: Long,
        val minExtractorSampleSize: Int,
        val maxExtractorSampleSize: Int,
        val codecInputCapacityMin: Int,
        val codecInputCapacityAvg: Long,
        val codecInputCapacityMax: Int,
        val safeMaxBatchBytes: Int,
        val effectiveBatchMaxBytesMin: Int,
        val effectiveBatchMaxBytesAvg: Long,
        val effectiveBatchMaxBytesMax: Int,
        val maxSamplesPerInputBuffer: Int,
        val inputBufferFillRatioAvg: String,
        val inputBufferFillRatioMax: Double,
        val inputBufferFillRatioMaxFormatted: String,
        val nearFullInputBuffers: Int,
        val underfilledInputBuffers: Int,
        val underfilledEndOfStream: Int,
        val underfilledNextSampleWouldNotFit: Int,
        val underfilledNonMonotonicTimestamp: Int,
        val underfilledSafetyCapReached: Int,
        val underfilledSingleSampleMode: Int,
        val timestampsMonotonic: Boolean,
        val failureStage: String?,
        val failureMessage: String?,
        val partialWavDeleted: Boolean,
    )
}

internal object Mp3ImportTiming : Mp3ImportTimingCore()
