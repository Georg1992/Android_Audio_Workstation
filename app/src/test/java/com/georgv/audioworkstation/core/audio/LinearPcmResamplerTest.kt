package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearPcmResamplerTest {

    @Test
    fun `resample passes through when rates match`() {
        val resampler = LinearPcmResampler(sourceRate = 48_000, targetRate = 48_000, channelCount = 1)
        val input = shortArrayOf(100, 200, 300)

        val output = resampler.resample(input, inputFrameCount = 3)

        assertArrayEqualsShort(input, output)
    }

    @Test
    fun `resample downsamples mono audio`() {
        val resampler = LinearPcmResampler(sourceRate = 48_000, targetRate = 24_000, channelCount = 1)
        val input = shortArrayOf(0, 1_000, 2_000, 3_000)

        val first = resampler.resample(input, inputFrameCount = 4)
        val second = resampler.resample(shortArrayOf(4_000, 5_000), inputFrameCount = 2)
        val total = first.size + second.size

        assertTrue(total >= 2)
    }

    @Test
    fun `resample preserves stereo channel pairs`() {
        val resampler = LinearPcmResampler(sourceRate = 44_100, targetRate = 48_000, channelCount = 2)
        val input = shortArrayOf(100, 200, 300, 400, 500, 600, 700, 800)

        val output = resampler.resample(input, inputFrameCount = 4)

        assertEquals(0, output.size % 2)
    }

    private fun assertArrayEqualsShort(expected: ShortArray, actual: ShortArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals(expected[index], actual[index])
        }
    }
}
