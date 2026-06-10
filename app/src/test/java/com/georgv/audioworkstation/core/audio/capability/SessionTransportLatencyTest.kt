package com.georgv.audioworkstation.core.audio.capability

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTransportLatencyTest {
    private fun capability(
        inputCaptureDelayMs: Double? = null,
        inputHalLatencyMs: Double? = null,
        outputLatencyMs: Double? = null,
    ): ResolvedAudioCapability =
        ResolvedAudioCapability(
            profile = null,
            profileId = "test",
            routeKey = "test",
            sampleRate = 44_100,
            outputLatencyMs = outputLatencyMs,
            inputCaptureDelayMs = inputCaptureDelayMs,
            inputHalLatencyMs = inputHalLatencyMs,
            roundTripMs = null,
            jitterMs = null,
            confidence = 0.75,
            lowLatencyOutputPathGranted = false,
            lowLatencyInputPathGranted = false,
            profileState = CapabilityProfileState.VALIDATED,
            routeUnchanged = true,
            validation = CapabilityValidationFlags(),
            warnings = emptyList(),
            dataComplete = true,
        )

    @Test
    fun `session input prefers calibrated capture delay over HAL`() {
        assertEquals(37.0, capability(inputCaptureDelayMs = 37.0, inputHalLatencyMs = 62.0).sessionInputLatencyMs(), 0.001)
    }

    @Test
    fun `session input ignores zero HAL and uses capture delay`() {
        assertEquals(41.0, capability(inputCaptureDelayMs = 41.0, inputHalLatencyMs = 0.0).sessionInputLatencyMs(), 0.001)
    }

    @Test
    fun `session input falls back to HAL when capture delay unknown`() {
        assertEquals(62.0, capability(inputHalLatencyMs = 62.0).sessionInputLatencyMs(), 0.001)
    }

    @Test
    fun `session output ignores non-positive profile floor`() {
        assertEquals(0.0, capability(outputLatencyMs = 0.0).sessionOutputLatencyMs(), 0.001)
        assertEquals(185.0, capability(outputLatencyMs = 185.0).sessionOutputLatencyMs(), 0.001)
    }
}
