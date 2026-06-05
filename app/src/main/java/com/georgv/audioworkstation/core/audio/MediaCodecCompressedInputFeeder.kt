package com.georgv.audioworkstation.core.audio

import java.nio.ByteBuffer

/**
 * Reads compressed samples from MediaExtractor and packs them into MediaCodec input buffers.
 *
 * Samples are always read into a scratch buffer at offset 0 (MediaExtractor requirement), then
 * copied into the codec buffer. For MP3, multiple consecutive samples are packed until
 * [CompressedImportDecodeConfig.effectiveBatchMaxBytes] is reached, which defaults to the codec
 * input buffer capacity on typical devices (8 KiB–64 KiB) and only clamps above [SAFE_MAX_BATCH_BYTES].
 */
internal class MediaCodecCompressedInputFeeder(
    private val reader: MediaCodecExtractReader,
    private val batchingEnabled: Boolean,
    maxSampleReadBytes: Int,
) {
    constructor(
        extractor: android.media.MediaExtractor,
        batchingEnabled: Boolean,
        maxSampleReadBytes: Int,
    ) : this(
        reader = MediaExtractorReader(extractor),
        batchingEnabled = batchingEnabled,
        maxSampleReadBytes = maxSampleReadBytes,
    )

    private val scratchBuffer = ByteBuffer.allocate(maxSampleReadBytes)
    private var pendingEndOfStream = false
    private var lastSampleTimeUs: Long? = null

    var timestampsMonotonic = true
        private set

    var inputSamplesRead = 0
        private set
    var extractorSampleSizeSum = 0L
        private set
    var extractorSampleSizeCount = 0
        private set
    var minExtractorSampleSize = Int.MAX_VALUE
        private set
    var maxExtractorSampleSize = 0
        private set

    var inputBytesSum = 0L
        private set
    var maxInputBytesPerBuffer = 0
        private set
    var samplesPerInputBufferSum = 0
        private set
    var samplesPerInputBufferCount = 0
        private set
    var maxSamplesPerInputBuffer = 0
        private set

    var codecInputCapacityMin = Int.MAX_VALUE
        private set
    var codecInputCapacityMax = 0
        private set
    var codecInputCapacitySum = 0L
        private set
    var codecInputCapacityCount = 0
        private set

    var effectiveBatchMaxBytesMin = Int.MAX_VALUE
        private set
    var effectiveBatchMaxBytesMax = 0
        private set
    var effectiveBatchMaxBytesSum = 0L
        private set
    var effectiveBatchMaxBytesCount = 0
        private set

    var inputBufferFillRatioSum = 0.0
        private set
    var inputBufferFillRatioCount = 0
        private set
    var maxInputBufferFillRatio = 0.0
        private set

    var nearFullInputBuffers = 0
        private set
    var underfilledInputBuffers = 0
        private set
    var underfilledEndOfStream = 0
        private set
    var underfilledNextSampleWouldNotFit = 0
        private set
    var underfilledNonMonotonicTimestamp = 0
        private set
    var underfilledSafetyCapReached = 0
        private set
    var underfilledSingleSampleMode = 0
        private set

    var inputBuffersQueued = 0
        private set

    val isBatchingEnabled: Boolean
        get() = batchingEnabled

    val safeMaxBatchBytes: Int
        get() = CompressedImportDecodeConfig.safeMaxBatchBytes()

    fun recordCodecInputCapacity(capacity: Int) {
        if (capacity <= 0) return
        codecInputCapacityCount++
        codecInputCapacitySum += capacity.toLong()
        if (capacity < codecInputCapacityMin) codecInputCapacityMin = capacity
        if (capacity > codecInputCapacityMax) codecInputCapacityMax = capacity
    }

    fun fillInputBuffer(inputBuffer: ByteBuffer): InputFillResult {
        if (pendingEndOfStream) {
            pendingEndOfStream = false
            return InputFillResult.EndOfStream
        }
        return if (batchingEnabled) {
            fillInputBufferBatched(inputBuffer)
        } else {
            fillInputBufferSingle(inputBuffer)
        }
    }

    private fun fillInputBufferSingle(inputBuffer: ByteBuffer): InputFillResult {
        val codecCapacity = inputBuffer.capacity()
        val effectiveMaxBytes = CompressedImportDecodeConfig.effectiveBatchMaxBytes(codecCapacity)
        recordEffectiveBatchMax(effectiveMaxBytes)
        val sampleSize = readNextSample()
        if (sampleSize < 0) {
            return InputFillResult.EndOfStream
        }
        require(sampleSize <= effectiveMaxBytes) {
            "Compressed sample size $sampleSize exceeds effective batch max $effectiveMaxBytes " +
                "(codec capacity $codecCapacity)"
        }
        copyScratchToInput(inputBuffer, writeOffset = 0, sampleSize = sampleSize)
        val presentationTimeUs = reader.sampleTimeUs()
        recordExtractorSample(sampleSize)
        recordTimestamp(presentationTimeUs)
        reader.advance()
        return InputFillResult.Queued(
            sizeBytes = sampleSize,
            presentationTimeUs = presentationTimeUs,
            samplesInBatch = 1,
            codecInputCapacity = codecCapacity,
            effectiveBatchMaxBytes = effectiveMaxBytes,
            stopReason = InputBufferFillStopReason.SINGLE_SAMPLE_MODE,
        )
    }

    private fun fillInputBufferBatched(inputBuffer: ByteBuffer): InputFillResult {
        val codecCapacity = inputBuffer.capacity()
        val effectiveMaxBytes = CompressedImportDecodeConfig.effectiveBatchMaxBytes(codecCapacity)
        recordEffectiveBatchMax(effectiveMaxBytes)
        val batch = packConsecutiveSamples(inputBuffer, codecCapacity, effectiveMaxBytes)
        if (batch.sampleCount == 0 && batch.stopReason == InputBufferFillStopReason.END_OF_STREAM) {
            return InputFillResult.EndOfStream
        }
        return InputFillResult.Queued(
            sizeBytes = batch.writeOffset,
            presentationTimeUs = batch.firstPresentationTimeUs,
            samplesInBatch = batch.sampleCount,
            codecInputCapacity = codecCapacity,
            effectiveBatchMaxBytes = effectiveMaxBytes,
            stopReason =
                classifyFinalStopReason(
                    sizeBytes = batch.writeOffset,
                    effectiveMaxBytes = effectiveMaxBytes,
                    codecCapacity = codecCapacity,
                    provisionalReason = batch.stopReason,
                ),
        )
    }

    private data class PackedBatch(
        val writeOffset: Int,
        val sampleCount: Int,
        val firstPresentationTimeUs: Long,
        val stopReason: InputBufferFillStopReason,
    )

    private fun packConsecutiveSamples(
        inputBuffer: ByteBuffer,
        codecCapacity: Int,
        effectiveMaxBytes: Int,
    ): PackedBatch {
        var writeOffset = 0
        var sampleCount = 0
        var firstPresentationTimeUs = 0L
        var stopReason = InputBufferFillStopReason.NEAR_FULL

        while (writeOffset < effectiveMaxBytes) {
            val remainingCapacity = effectiveMaxBytes - writeOffset
            val sampleSize = readNextSample()
            if (sampleSize < 0) {
                if (writeOffset == 0) {
                    return PackedBatch(0, 0, 0L, InputBufferFillStopReason.END_OF_STREAM)
                }
                pendingEndOfStream = true
                stopReason = InputBufferFillStopReason.END_OF_STREAM
                break
            }
            if (sampleSize > remainingCapacity) {
                if (writeOffset == 0) {
                    require(sampleSize <= effectiveMaxBytes) {
                        "Compressed sample size $sampleSize exceeds effective batch max " +
                            "$effectiveMaxBytes (codec capacity $codecCapacity)"
                    }
                }
                stopReason = InputBufferFillStopReason.NEXT_SAMPLE_WOULD_NOT_FIT
                break
            }

            val sampleTimeUs = reader.sampleTimeUs()
            if (sampleCount > 0) {
                val previousTimeUs = lastSampleTimeUs
                if (previousTimeUs != null && sampleTimeUs < previousTimeUs) {
                    timestampsMonotonic = false
                    stopReason = InputBufferFillStopReason.NON_MONOTONIC_TIMESTAMP
                    break
                }
            } else {
                firstPresentationTimeUs = sampleTimeUs
            }

            copyScratchToInput(inputBuffer, writeOffset = writeOffset, sampleSize = sampleSize)
            recordExtractorSample(sampleSize)
            writeOffset += sampleSize
            sampleCount++
            recordTimestamp(sampleTimeUs)
            reader.advance()

            if (writeOffset >= effectiveMaxBytes) {
                stopReason =
                    if (effectiveMaxBytes < codecCapacity) {
                        InputBufferFillStopReason.SAFETY_CAP_REACHED
                    } else {
                        InputBufferFillStopReason.NEAR_FULL
                    }
                break
            }
        }

        require(sampleCount > 0) {
            "No compressed samples fit into effective batch max $effectiveMaxBytes " +
                "(codec capacity $codecCapacity)"
        }
        return PackedBatch(writeOffset, sampleCount, firstPresentationTimeUs, stopReason)
    }

    private fun classifyFinalStopReason(
        sizeBytes: Int,
        effectiveMaxBytes: Int,
        codecCapacity: Int,
        provisionalReason: InputBufferFillStopReason,
    ): InputBufferFillStopReason {
        if (provisionalReason != InputBufferFillStopReason.NEAR_FULL) {
            return provisionalReason
        }
        val fillRatio = sizeBytes.toDouble() / effectiveMaxBytes.coerceAtLeast(1)
        return when {
            fillRatio >= CompressedImportDecodeConfig.NEAR_FULL_FILL_RATIO -> InputBufferFillStopReason.NEAR_FULL
            effectiveMaxBytes < codecCapacity && sizeBytes >= effectiveMaxBytes ->
                InputBufferFillStopReason.SAFETY_CAP_REACHED
            else -> InputBufferFillStopReason.NEXT_SAMPLE_WOULD_NOT_FIT
        }
    }

    private fun readNextSample(): Int {
        scratchBuffer.clear()
        return reader.readSampleData(scratchBuffer, 0)
    }

    private fun copyScratchToInput(inputBuffer: ByteBuffer, writeOffset: Int, sampleSize: Int) {
        scratchBuffer.position(0)
        scratchBuffer.limit(sampleSize)
        inputBuffer.position(writeOffset)
        inputBuffer.put(scratchBuffer)
    }

    private fun recordExtractorSample(sampleSize: Int) {
        inputSamplesRead++
        extractorSampleSizeSum += sampleSize.toLong()
        extractorSampleSizeCount++
        if (sampleSize < minExtractorSampleSize) minExtractorSampleSize = sampleSize
        if (sampleSize > maxExtractorSampleSize) maxExtractorSampleSize = sampleSize
    }

    private fun recordEffectiveBatchMax(effectiveMaxBytes: Int) {
        if (effectiveMaxBytes <= 0) return
        effectiveBatchMaxBytesCount++
        effectiveBatchMaxBytesSum += effectiveMaxBytes.toLong()
        if (effectiveMaxBytes < effectiveBatchMaxBytesMin) effectiveBatchMaxBytesMin = effectiveMaxBytes
        if (effectiveMaxBytes > effectiveBatchMaxBytesMax) effectiveBatchMaxBytesMax = effectiveMaxBytes
    }

    private fun recordTimestamp(sampleTimeUs: Long) {
        val previousTimeUs = lastSampleTimeUs
        if (previousTimeUs != null && sampleTimeUs < previousTimeUs) {
            timestampsMonotonic = false
        }
        lastSampleTimeUs = sampleTimeUs
    }

    fun recordInputBufferQueued(queued: InputFillResult.Queued) {
        inputBuffersQueued++
        val sizeBytes = queued.sizeBytes
        if (sizeBytes > 0) {
            inputBytesSum += sizeBytes.toLong()
            if (sizeBytes > maxInputBytesPerBuffer) {
                maxInputBytesPerBuffer = sizeBytes
            }
        }
        val samplesInBatch = queued.samplesInBatch
        if (samplesInBatch > 0) {
            samplesPerInputBufferSum += samplesInBatch
            samplesPerInputBufferCount++
            if (samplesInBatch > maxSamplesPerInputBuffer) {
                maxSamplesPerInputBuffer = samplesInBatch
            }
        }
        val effectiveMax = queued.effectiveBatchMaxBytes.coerceAtLeast(1)
        val fillRatio = sizeBytes.toDouble() / effectiveMax
        inputBufferFillRatioSum += fillRatio
        inputBufferFillRatioCount++
        if (fillRatio > maxInputBufferFillRatio) {
            maxInputBufferFillRatio = fillRatio
        }
        when (queued.stopReason) {
            InputBufferFillStopReason.NEAR_FULL -> nearFullInputBuffers++
            InputBufferFillStopReason.END_OF_STREAM -> {
                underfilledInputBuffers++
                underfilledEndOfStream++
            }
            InputBufferFillStopReason.NEXT_SAMPLE_WOULD_NOT_FIT -> {
                if (fillRatio >= CompressedImportDecodeConfig.NEAR_FULL_FILL_RATIO) {
                    nearFullInputBuffers++
                } else {
                    underfilledInputBuffers++
                    underfilledNextSampleWouldNotFit++
                }
            }
            InputBufferFillStopReason.NON_MONOTONIC_TIMESTAMP -> {
                underfilledInputBuffers++
                underfilledNonMonotonicTimestamp++
            }
            InputBufferFillStopReason.SAFETY_CAP_REACHED -> {
                if (fillRatio >= CompressedImportDecodeConfig.NEAR_FULL_FILL_RATIO) {
                    nearFullInputBuffers++
                } else {
                    underfilledInputBuffers++
                    underfilledSafetyCapReached++
                }
            }
            InputBufferFillStopReason.SINGLE_SAMPLE_MODE -> {
                if (fillRatio >= CompressedImportDecodeConfig.NEAR_FULL_FILL_RATIO) {
                    nearFullInputBuffers++
                } else {
                    underfilledInputBuffers++
                    underfilledSingleSampleMode++
                }
            }
        }
    }

    sealed class InputFillResult {
        data class Queued(
            val sizeBytes: Int,
            val presentationTimeUs: Long,
            val samplesInBatch: Int,
            val codecInputCapacity: Int,
            val effectiveBatchMaxBytes: Int,
            val stopReason: InputBufferFillStopReason,
        ) : InputFillResult()

        data object EndOfStream : InputFillResult()
    }
}
