package com.georgv.audioworkstation.core.audio.capability

import android.util.Log

data class CapabilityProfileValidationResult(
    val calibration: MeasuredCalibrationData,
    val flags: CapabilityValidationFlags,
    val warnings: List<String>,
)

object CapabilityProfileValidator {
    private const val TAG = "AudioSyncDiag"

    fun validate(profile: DeviceAudioCapabilityProfile): CapabilityProfileValidationResult {
        val warnings = mutableListOf<String>()
        var calibration = profile.calibration

        val outputMs = DeviceAudioCapabilityClassifier.effectiveOutputLatencyMs(profile)
        val roundTripMs = calibration.measuredRoundTripMs
        val captureDelayMs = calibration.estimatedTrueCaptureDelayMs

        if (roundTripMs != null && (!roundTripMs.isFinite() || roundTripMs < 0.0)) {
            warnings += "round_trip_invalid"
            calibration = calibration.copy(measuredRoundTripMs = null)
        }

        if (outputMs != null && (!outputMs.isFinite() || outputMs < 0.0)) {
            warnings += "output_latency_invalid"
        }

        val rawCapture =
            if (roundTripMs != null && outputMs != null) {
                CaptureDelayEstimator.estimateTrueCaptureDelayMs(roundTripMs, outputMs)
            } else {
                captureDelayMs
            }

        val sanitizedCapture = sanitizeCaptureDelay(rawCapture, warnings)
        calibration =
            calibration.copy(
                estimatedTrueCaptureDelayMs = sanitizedCapture,
            )

        if (outputMs != null &&
            calibration.measuredRoundTripMs != null &&
            outputMs > calibration.measuredRoundTripMs
        ) {
            warnings += "measurement_inconsistent"
        }

        val validationFlags =
            CapabilityValidationFlags(
                measurementInconsistent = warnings.contains("measurement_inconsistent"),
                captureDelayInvalid = warnings.contains("capture_delay_negative"),
                captureDelayUnknown = sanitizedCapture == null,
                outputLatencyInvalid = warnings.contains("output_latency_invalid"),
                roundTripInvalid = warnings.contains("round_trip_invalid"),
            )

        logValidation(profile.routeKey, validationFlags, warnings)
        return CapabilityProfileValidationResult(
            calibration = calibration,
            flags = validationFlags,
            warnings = warnings,
        )
    }

    private fun sanitizeCaptureDelay(
        captureDelayMs: Double?,
        warnings: MutableList<String>,
    ): Double? {
        if (captureDelayMs == null || !captureDelayMs.isFinite()) {
            warnings += "capture_delay_unknown"
            return null
        }
        if (captureDelayMs < 0.0) {
            warnings += "capture_delay_negative"
            return null
        }
        return captureDelayMs
    }

    private fun logValidation(
        routeKey: String,
        flags: CapabilityValidationFlags,
        warnings: List<String>,
    ) {
        Log.i(
            TAG,
            "[AUDIO_PROFILE_VALIDATION] " +
                "routeKey=$routeKey " +
                "measurementInconsistent=${flags.measurementInconsistent} " +
                "captureDelayInvalid=${flags.captureDelayInvalid} " +
                "captureDelayUnknown=${flags.captureDelayUnknown} " +
                "outputLatencyInvalid=${flags.outputLatencyInvalid} " +
                "roundTripInvalid=${flags.roundTripInvalid} " +
                "warnings=${warnings.joinToString(", ").ifEmpty { "none" }}",
        )
    }
}
