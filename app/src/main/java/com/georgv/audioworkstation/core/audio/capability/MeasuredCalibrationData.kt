package com.georgv.audioworkstation.core.audio.capability

/** Acoustic / click calibration — never mixed with startup-only timing. */
data class MeasuredCalibrationData(
    val measuredRoundTripMs: Double? = null,
    val measuredJitterMs: Double? = null,
    val estimatedOutputLatencyMs: Double? = null,
    val estimatedTrueCaptureDelayMs: Double? = null,
    val calibrationConfidence: Double = 0.0,
    val calibratedAt: Long = 0L,
) {
    fun hasCaptureDelayEstimate(): Boolean =
        estimatedTrueCaptureDelayMs != null &&
            estimatedTrueCaptureDelayMs.isFinite() &&
            estimatedTrueCaptureDelayMs >= 0.0
}
