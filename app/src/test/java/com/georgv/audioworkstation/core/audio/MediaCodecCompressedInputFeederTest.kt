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

        val second = feeder.fillInputBuffer(inputBuffer) as MediaCodecCompressedInputFeeder.InputFillResult.Queued
        assertEquals(700, second.sizeBytes)
        assertEquals(1, second.samplesInBatch)
        assertEquals(3_000L, second.presentationTimeUs)

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

        val second = feeder.fillInputBuffer(inputBuffer) as MediaCodecCompressedInputFeeder.InputFillResult.Queued
        assertEquals(300, second.sizeBytes)
        assertEquals(1, second.samplesInBatch)
        assertEquals(500L, second.presentationTimeUs)
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
