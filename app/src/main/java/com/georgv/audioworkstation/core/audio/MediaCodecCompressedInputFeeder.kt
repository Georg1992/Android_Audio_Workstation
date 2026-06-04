package com.georgv.audioworkstation.core.audio

import java.nio.ByteBuffer

/**
 * Reads compressed samples from MediaExtractor and packs them into MediaCodec input buffers.
 *
 * Samples are always read into a scratch buffer at offset 0 (MediaExtractor requirement), then
 * copied into the codec buffer. For MP3, multiple consecutive samples are packed until the codec
 * input buffer capacity is reached, which avoids thousands of per-frame queue operations.
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

    var codecInputCapacityMin = Int.MAX_VALUE
        private set
    var codecInputCapacityMax = 0
        private set
    var codecInputCapacitySum = 0L
        private set
    var codecInputCapacityCount = 0
        private set

    var inputBuffersQueued = 0
        private set

    val isBatchingEnabled: Boolean
        get() = batchingEnabled

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
        val capacity = inputBuffer.capacity()
        val sampleSize = readNextSample()
        if (sampleSize < 0) {
            return InputFillResult.EndOfStream
        }
        require(sampleSize <= capacity) {
            "Compressed sample size $sampleSize exceeds codec input buffer capacity $capacity"
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
        )
    }

    private fun fillInputBufferBatched(inputBuffer: ByteBuffer): InputFillResult {
        val capacity = inputBuffer.capacity()
        var writeOffset = 0
        var sampleCount = 0
        var firstPresentationTimeUs = 0L

        while (writeOffset < capacity) {
            val remainingCapacity = capacity - writeOffset
            val sampleSize = readNextSample()
            if (sampleSize < 0) {
                if (writeOffset == 0) {
                    return InputFillResult.EndOfStream
                }
                pendingEndOfStream = true
                break
            }
            if (sampleSize > remainingCapacity) {
                if (writeOffset == 0) {
                    require(sampleSize <= capacity) {
                        "Compressed sample size $sampleSize exceeds codec input buffer capacity $capacity"
                    }
                }
                break
            }

            val sampleTimeUs = reader.sampleTimeUs()
            if (sampleCount > 0) {
                val previousTimeUs = lastSampleTimeUs
                if (previousTimeUs != null && sampleTimeUs < previousTimeUs) {
                    timestampsMonotonic = false
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
        }

        require(sampleCount > 0) {
            "No compressed samples fit into codec input buffer capacity $capacity"
        }

        return InputFillResult.Queued(
            sizeBytes = writeOffset,
            presentationTimeUs = firstPresentationTimeUs,
            samplesInBatch = sampleCount,
        )
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

    private fun recordTimestamp(sampleTimeUs: Long) {
        val previousTimeUs = lastSampleTimeUs
        if (previousTimeUs != null && sampleTimeUs < previousTimeUs) {
            timestampsMonotonic = false
        }
        lastSampleTimeUs = sampleTimeUs
    }

    fun recordInputBufferQueued(sizeBytes: Int, samplesInBatch: Int) {
        inputBuffersQueued++
        if (sizeBytes > 0) {
            inputBytesSum += sizeBytes.toLong()
            if (sizeBytes > maxInputBytesPerBuffer) {
                maxInputBytesPerBuffer = sizeBytes
            }
        }
        if (samplesInBatch > 0) {
            samplesPerInputBufferSum += samplesInBatch
            samplesPerInputBufferCount++
        }
    }

    sealed class InputFillResult {
        data class Queued(
            val sizeBytes: Int,
            val presentationTimeUs: Long,
            val samplesInBatch: Int,
        ) : InputFillResult()

        data object EndOfStream : InputFillResult()
    }
}
