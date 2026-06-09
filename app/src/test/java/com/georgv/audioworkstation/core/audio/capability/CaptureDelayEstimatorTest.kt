package com.georgv.audioworkstation.core.audio.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureDelayEstimatorTest {
    @Test
    fun estimate_returnsNullForNegativeCaptureDelay() {
        val delay =
            CaptureDelayEstimator.estimateTrueCaptureDelayMs(
                measuredRoundTripMs = 50.0,
                estimatedOutputLatencyMs = 80.0,
            )
        assertNull(delay)
    }

    @Test
    fun estimate_returnsPositiveCaptureDelay() {
        val delay =
            CaptureDelayEstimator.estimateTrueCaptureDelayMs(
                measuredRoundTripMs = 100.0,
                estimatedOutputLatencyMs = 12.0,
            )
        assertEquals(88.0, delay!!, 0.001)
    }
}
