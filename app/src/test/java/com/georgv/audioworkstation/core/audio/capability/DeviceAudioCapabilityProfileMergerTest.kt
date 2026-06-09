package com.georgv.audioworkstation.core.audio.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAudioCapabilityProfileMergerTest {
    @Test
    fun merge_keepsHigherConfidenceCalibration() {
        val existing = sampleCapabilityProfile(calibrationConfidence = 0.85)
        val incoming =
            existing.copy(
                calibration =
                    MeasuredCalibrationData(
                        measuredRoundTripMs = 50.0,
                        calibrationConfidence = 0.5,
                        calibratedAt = 2_000_000_000_000L,
                    ),
                updatedAt = 2_000_000_000_000L,
            )

        val merged = DeviceAudioCapabilityProfileMerger.merge(existing, incoming)

        assertEquals(95.0, merged.calibration.measuredRoundTripMs!!, 0.001)
        assertEquals(0.85, merged.calibration.calibrationConfidence, 0.001)
    }

    @Test
    fun merge_acceptsHigherConfidenceCalibration() {
        val existing = sampleCapabilityProfile(calibrationConfidence = 0.65)
        val incoming =
            existing.copy(
                calibration =
                    existing.calibration.copy(
                        measuredRoundTripMs = 100.0,
                        estimatedTrueCaptureDelayMs = 88.0,
                        calibrationConfidence = 0.9,
                        calibratedAt = 2_000_000_000_000L,
                    ),
                updatedAt = 2_000_000_000_000L,
            )

        val merged = DeviceAudioCapabilityProfileMerger.merge(existing, incoming)

        assertEquals(100.0, merged.calibration.measuredRoundTripMs!!, 0.001)
        assertEquals(0.9, merged.calibration.calibrationConfidence, 0.001)
    }

    @Test
    fun classifier_derivesLatencyTiersFromCalibratedProfile() {
        val profile = sampleCapabilityProfile()
        assertTrue(profile.derived.inputTier != LatencyTier.UNKNOWN)
        assertTrue(profile.derived.outputTier != LatencyTier.UNKNOWN)
    }

    @Test
    fun classifier_marksInputTierUnknownWithoutCaptureDelay() {
        val profile =
            sampleCapabilityProfile().copy(
                calibration = MeasuredCalibrationData(calibrationConfidence = 0.85),
                derived = DerivedCapabilityData(),
            )
        val classified = DeviceAudioCapabilityClassifier.classify(profile)
        assertEquals(LatencyTier.UNKNOWN, classified.inputTier)
    }

    @Test
    fun captureDelay_neverUsesStartupTiming() {
        val delay =
            CaptureDelayEstimator.estimateTrueCaptureDelayMs(
                measuredRoundTripMs = 100.0,
                estimatedOutputLatencyMs = 12.0,
            )
        assertEquals(88.0, delay!!, 0.001)
    }
}
