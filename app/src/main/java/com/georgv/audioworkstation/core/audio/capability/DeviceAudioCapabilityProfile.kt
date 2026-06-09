package com.georgv.audioworkstation.core.audio.capability

/**
 * Single source of truth for device + route + sample rate + I/O backend capability.
 * Diagnostics and compensation read this; playback behavior does not depend on it yet.
 */
data class DeviceAudioCapabilityProfile(
    val profileId: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val sdkInt: Int,
    val routeKey: String,
    val routeType: AudioRouteType,
    val sampleRate: Int,
    val output: StreamCapabilitySide,
    val input: StreamCapabilitySide,
    val calibration: MeasuredCalibrationData,
    val startup: StartupMetricsData,
    val derived: DerivedCapabilityData,
    val measurementHistory: CapabilityMeasurementHistory = CapabilityMeasurementHistory(),
    val validation: CapabilityValidationFlags = CapabilityValidationFlags(),
    val recentRoundTripMs: List<Double> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
) {
    val highLatencyOutputRoute: Boolean
        get() = output.highLatencyRoute || routeType.isHighLatencyRoute()

    fun overallConfidence(): Double {
        val calibration = calibration.calibrationConfidence
        val output = output.latencyConfidence
        val input = input.latencyConfidence
        return listOf(calibration, output, input).maxOrNull() ?: 0.0
    }
}
