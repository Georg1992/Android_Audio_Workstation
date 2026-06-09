package com.georgv.audioworkstation.core.audio.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityProfileValidatorTest {
    @Test
    fun validate_rejectsNegativeCaptureDelay() {
        val profile =
            sampleCapabilityProfile(
                halOutputMs = 80.0,
                roundTripMs = 50.0,
                captureDelayMs = -5.0,
            )
        val result = CapabilityProfileValidator.validate(profile)
        assertNull(result.calibration.estimatedTrueCaptureDelayMs)
        assertTrue(result.flags.captureDelayUnknown)
        assertTrue(result.warnings.contains("capture_delay_unknown"))
    }

    @Test
    fun validate_marksMeasurementInconsistentWhenOutputExceedsRoundTrip() {
        val profile =
            sampleCapabilityProfile(
                halOutputMs = 200.0,
                roundTripMs = 150.0,
                captureDelayMs = 0.0,
            )
        val result = CapabilityProfileValidator.validate(profile)
        assertTrue(result.flags.measurementInconsistent)
        assertTrue(result.warnings.contains("measurement_inconsistent"))
    }
}
