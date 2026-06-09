package com.georgv.audioworkstation.core.audio.capability

import kotlin.math.roundToLong

/**
 * True capture delay from acoustic measurement minus HAL output latency.
 * Never derives capture delay from startup / arm-to-first-input timing.
 */
object CaptureDelayEstimator {
    fun estimateTrueCaptureDelayMs(
        measuredRoundTripMs: Double?,
        estimatedOutputLatencyMs: Double?,
    ): Double? {
        if (measuredRoundTripMs == null || !measuredRoundTripMs.isFinite() || measuredRoundTripMs < 0.0) {
            return null
        }
        val outputMs = estimatedOutputLatencyMs
        if (outputMs == null || !outputMs.isFinite() || outputMs < 0.0) {
            return null
        }
        val captureDelay = measuredRoundTripMs - outputMs.roundToLong().toDouble()
        if (!captureDelay.isFinite() || captureDelay < 0.0) {
            return null
        }
        return captureDelay
    }

    fun jitterFromMeasurements(measurements: List<Double>): Double? {
        if (measurements.size < 2) {
            return measurements.singleOrNull()
        }
        val sorted = measurements.sorted()
        return sorted.last() - sorted.first()
    }
}
