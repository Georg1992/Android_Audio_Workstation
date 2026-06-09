package com.georgv.audioworkstation.core.audio.capability.audit

import com.georgv.audioworkstation.core.audio.capability.DeviceAudioCapabilityProfile
import com.georgv.audioworkstation.core.audio.capability.MeasuredCalibrationData
import com.georgv.audioworkstation.core.audio.capability.StartupMetricsData
import com.georgv.audioworkstation.core.audio.capability.StreamCapabilitySide

data class CapabilityCompletenessResult(
    val outputStreamConfigCaptured: Boolean,
    val inputStreamConfigCaptured: Boolean,
    val outputHardwareFloorCaptured: Boolean,
    val trueCaptureDelayCaptured: Boolean,
    val roundTripCaptured: Boolean,
    val jitterCaptured: Boolean,
    val startupMetricsCaptured: Boolean,
    val routeKey: String,
    val sampleRate: Int,
    val missingFields: List<String>,
) {
    val missingFieldsLabel: String =
        if (missingFields.isEmpty()) {
            "none"
        } else {
            missingFields.joinToString(",")
        }
}

object CapabilityCompletenessEvaluator {
    fun evaluate(profile: DeviceAudioCapabilityProfile?): CapabilityCompletenessResult {
        if (profile == null) {
            return emptyResult(routeKey = "unknown", sampleRate = 0)
        }
        val missing = mutableListOf<String>()
        val outputStreamConfigCaptured = streamConfigCaptured(profile.output, "output", missing)
        val inputStreamConfigCaptured = streamConfigCaptured(profile.input, "input", missing)
        val outputHardwareFloorCaptured = hardwareFloorCaptured(profile.output, missing)
        val trueCaptureDelayCaptured = captureDelayCaptured(profile.calibration, missing)
        val roundTripCaptured = roundTripCaptured(profile.calibration, missing)
        val jitterCaptured = jitterCaptured(profile.calibration, profile.recentRoundTripMs, missing)
        val startupMetricsCaptured = startupMetricsCaptured(profile.startup, missing)
        return CapabilityCompletenessResult(
            outputStreamConfigCaptured = outputStreamConfigCaptured,
            inputStreamConfigCaptured = inputStreamConfigCaptured,
            outputHardwareFloorCaptured = outputHardwareFloorCaptured,
            trueCaptureDelayCaptured = trueCaptureDelayCaptured,
            roundTripCaptured = roundTripCaptured,
            jitterCaptured = jitterCaptured,
            startupMetricsCaptured = startupMetricsCaptured,
            routeKey = profile.routeKey,
            sampleRate = profile.sampleRate,
            missingFields = missing,
        )
    }

    private fun emptyResult(routeKey: String, sampleRate: Int): CapabilityCompletenessResult =
        CapabilityCompletenessResult(
            outputStreamConfigCaptured = false,
            inputStreamConfigCaptured = false,
            outputHardwareFloorCaptured = false,
            trueCaptureDelayCaptured = false,
            roundTripCaptured = false,
            jitterCaptured = false,
            startupMetricsCaptured = false,
            routeKey = routeKey,
            sampleRate = sampleRate,
            missingFields =
                listOf(
                    "profile",
                    "output_stream_config",
                    "input_stream_config",
                    "output_hardware_floor",
                    "true_capture_delay",
                    "round_trip",
                    "jitter",
                    "startup_metrics",
                ),
        )

    private fun streamConfigCaptured(
        side: StreamCapabilitySide,
        prefix: String,
        missing: MutableList<String>,
    ): Boolean {
        val captured =
            side.actualAudioApi != "Unknown" &&
                side.bufferSizeFrames > 0 &&
                side.framesPerBurst > 0
        if (!captured) {
            missing += "${prefix}_stream_config"
        }
        return captured
    }

    private fun hardwareFloorCaptured(
        side: StreamCapabilitySide,
        missing: MutableList<String>,
    ): Boolean {
        val captured = side.hasValidHalLatency() || side.bufferSizeMs > 0.0
        if (!captured) {
            missing += "output_hardware_floor"
        }
        return captured
    }

    private fun captureDelayCaptured(
        calibration: MeasuredCalibrationData,
        missing: MutableList<String>,
    ): Boolean {
        val captured = calibration.hasCaptureDelayEstimate()
        if (!captured) {
            missing += "true_capture_delay"
        }
        return captured
    }

    private fun roundTripCaptured(
        calibration: MeasuredCalibrationData,
        missing: MutableList<String>,
    ): Boolean {
        val captured =
            calibration.measuredRoundTripMs != null &&
                calibration.measuredRoundTripMs.isFinite() &&
                calibration.measuredRoundTripMs >= 0.0
        if (!captured) {
            missing += "round_trip"
        }
        return captured
    }

    private fun jitterCaptured(
        calibration: MeasuredCalibrationData,
        recentRoundTripMs: List<Double>,
        missing: MutableList<String>,
    ): Boolean {
        val captured =
            (calibration.measuredJitterMs != null &&
                calibration.measuredJitterMs.isFinite() &&
                calibration.measuredJitterMs >= 0.0) ||
                recentRoundTripMs.size >= 2
        if (!captured) {
            missing += "jitter"
        }
        return captured
    }

    private fun startupMetricsCaptured(
        startup: StartupMetricsData,
        missing: MutableList<String>,
    ): Boolean {
        val captured = startup.startupMetricsUpdatedAt > 0L
        if (!captured) {
            missing += "startup_metrics"
        }
        return captured
    }
}
