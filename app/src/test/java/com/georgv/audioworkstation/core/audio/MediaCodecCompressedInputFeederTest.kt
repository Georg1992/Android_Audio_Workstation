package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MediaCodecCompressedInputFeederTest {

    @Test
    fun `batched fill packs consecutive samples until buffer full`() {
        val reader =
            FakeExtractReader(
                sampleSizes = intArrayOf(400, 500, 600, 700),
            )
        val feeder = MediaCodecCompressedInputFeeder(reader, batchingEnabled = true, maxSampleReadBytes = 8_192)
        val inputBuffer = ByteBuffer.allocate(1_500).order(ByteOrder.LITTLE_ENDIAN)

        val first = feeder.fillInputBuffer(inputBuffer) as MediaCodecCompressedInputFeeder.InputFillResult.Queued
        assertEquals(1_500, first.sizeBytes)
        assertEquals(3, first.samplesInBatch)
        assertEquals(0L, first.presentationTimeUs)
        assertEquals(1_500, first.codecInputCapacity)
        assertEquals(1_500, first.effectiveBatchMaxBytes)
        assertEquals(InputBufferFillStopReason.NEAR_FULL, first.stopReason)

        val second = feeder.fillInputBuffer(inputBuffer) as MediaCodecCompressedInputFeeder.InputFillResult.Queued
        assertEquals(700, second.sizeBytes)
        assertEquals(1, second.samplesInBatch)
        assertEquals(3_000L, second.presentationTimeUs)
        assertEquals(InputBufferFillStopReason.END_OF_STREAM, second.stopReason)

        val eos = feeder.fillInputBuffer(inputBuffer)
        assertTrue(eos is MediaCodecCompressedInputFeeder.InputFillResult.EndOfStream)
        assertEquals(4, feeder.inputSamplesRead)
        assertTrue(feeder.timestampsMonotonic)
    }

    @Test
    fun `single-sample mode queues one sample per buffer`() {
        val reader = FakeExtractReader(sampleSizes = intArrayOf(400, 500))
        val feeder = MediaCodecCompressedInputFeeder(reader, batchingEnabled = false, maxSampleReadBytes = 8_192)
        val inputBuffer = ByteBuffer.allocate(4_096)

        val first = feeder.fillInputBuffer(inputBuffer) as MediaCodecCompressedInputFeeder.InputFillResult.Queued
        assertEquals(400, first.sizeBytes)
        assertEquals(1, first.samplesInBatch)

        val second = feeder.fillInputBuffer(inputBuffer) as MediaCodecCompressedInputFeeder.InputFillResult.Queued
        assertEquals(500, second.sizeBytes)
        assertEquals(1, second.samplesInBatch)
    }

    @Test
    fun `non-monotonic timestamp stops batching current buffer`() {
        val reader =
            FakeExtractReader(
                sampleSizes = intArrayOf(400, 500, 300),
                sampleTimesUs = longArrayOf(0L, 1_000L, 500L),
            )
        val feeder = MediaCodecCompressedInputFeeder(reader, batchingEnabled = true, maxSampleReadBytes = 8_192)
        val inputBuffer = ByteBuffer.allocate(4_096)

        val first = feeder.fillInputBuffer(inputBuffer) as MediaCodecCompressedInputFeeder.InputFillResult.Queued
        assertEquals(900, first.sizeBytes)
        assertEquals(2, first.samplesInBatch)
        assertFalse(feeder.timestampsMonotonic)
        assertEquals(InputBufferFillStopReason.NON_MONOTONIC_TIMESTAMP, first.stopReason)

        val second = feeder.fillInputBuffer(inputBuffer) as MediaCodecCompressedInputFeeder.InputFillResult.Queued
        assertEquals(300, second.sizeBytes)
        assertEquals(1, second.samplesInBatch)
        assertEquals(500L, second.presentationTimeUs)
    }

    @Test
    fun `effective batch max uses codec capacity when below safe cap`() {
        assertEquals(8_192, CompressedImportDecodeConfig.effectiveBatchMaxBytes(8_192))
        assertEquals(65_536, CompressedImportDecodeConfig.effectiveBatchMaxBytes(65_536))
        assertEquals(131_072, CompressedImportDecodeConfig.effectiveBatchMaxBytes(131_072))
        assertEquals(262_144, CompressedImportDecodeConfig.effectiveBatchMaxBytes(262_144))
    }

    @Test
    fun `effective batch max clamps to safe cap when codec capacity is larger`() {
        val safeCap = CompressedImportDecodeConfig.SAFE_MAX_BATCH_BYTES
        assertEquals(safeCap, CompressedImportDecodeConfig.effectiveBatchMaxBytes(safeCap + 1))
        assertEquals(safeCap, CompressedImportDecodeConfig.effectiveBatchMaxBytes(512 * 1024))
    }

    @Test
    fun `batched fill stops at safety cap when codec capacity exceeds safe max`() {
        val safeCap = CompressedImportDecodeConfig.SAFE_MAX_BATCH_BYTES
        val sampleSize = 256
        val sampleCount = safeCap / sampleSize
        val reader = FakeExtractReader(sampleSizes = IntArray(sampleCount + 1) { sampleSize })
        val feeder =
            MediaCodecCompressedInputFeeder(
                reader = reader,
                batchingEnabled = true,
                maxSampleReadBytes = safeCap,
            )
        val inputBuffer = ByteBuffer.allocate(512 * 1024)

        val first = feeder.fillInputBuffer(inputBuffer) as MediaCodecCompressedInputFeeder.InputFillResult.Queued
        assertEquals(safeCap, first.sizeBytes)
        assertEquals(sampleCount, first.samplesInBatch)
        assertEquals(safeCap, first.effectiveBatchMaxBytes)
        assertEquals(InputBufferFillStopReason.SAFETY_CAP_REACHED, first.stopReason)
    }

    @Test
    fun `recordInputBufferQueued tracks fill utilization counters`() {
        val reader = FakeExtractReader(sampleSizes = intArrayOf(960, 960))
        val feeder = MediaCodecCompressedInputFeeder(reader, batchingEnabled = true, maxSampleReadBytes = 8_192)
        val inputBuffer = ByteBuffer.allocate(1_000)

        val queued = feeder.fillInputBuffer(inputBuffer) as MediaCodecCompressedInputFeeder.InputFillResult.Queued
        feeder.recordInputBufferQueued(queued)

        assertEquals(1, feeder.inputBuffersQueued)
        assertEquals(960, feeder.maxInputBytesPerBuffer)
        assertEquals(1, feeder.maxSamplesPerInputBuffer)
        assertEquals(1, feeder.nearFullInputBuffers)
        assertEquals(0, feeder.underfilledInputBuffers)
    }

    @Test
    fun `single-sample mode reports single sample stop reason`() {
        val reader = FakeExtractReader(sampleSizes = intArrayOf(400))
        val feeder = MediaCodecCompressedInputFeeder(reader, batchingEnabled = false, maxSampleReadBytes = 8_192)
        val inputBuffer = ByteBuffer.allocate(4_096)

        val queued = feeder.fillInputBuffer(inputBuffer) as MediaCodecCompressedInputFeeder.InputFillResult.Queued
        assertEquals(InputBufferFillStopReason.SINGLE_SAMPLE_MODE, queued.stopReason)
    }

    private class FakeExtractReader(
        sampleSizes: IntArray,
        sampleTimesUs: LongArray = sampleSizes.indices.map { it * 1_000L }.toLongArray(),
    ) : MediaCodecExtractReader {
        private var index = 0
        private val sizes = sampleSizes
        private val times = sampleTimesUs

        init {
            require(sampleSizes.size == sampleTimesUs.size)
        }

        override fun readSampleData(byteBuf: ByteBuffer, offset: Int): Int {
            if (index >= sizes.size) return -1
            val size = sizes[index]
            byteBuf.position(offset)
            byteBuf.limit(offset + size)
            repeat(size) { byteBuf.put(offset + it, 0x5A.toByte()) }
            return size
        }

        override fun sampleTimeUs(): Long = times[index]

        override fun advance() {
            if (index < sizes.size) {
                index++
            }
        }
    }
}
