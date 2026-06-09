package com.georgv.audioworkstation.core.audio.capability

/** HAL / Oboe stream capability for one direction (output or input). */
data class StreamCapabilitySide(
    val requestedAudioApi: String = "Unspecified",
    val actualAudioApi: String = "Unknown",
    val requestedPerformanceMode: String = "LowLatency",
    val actualPerformanceMode: String = "Unknown",
    val performanceModeGranted: Boolean = false,
    val requestedSharingMode: String = "Shared",
    val actualSharingMode: String = "Unknown",
    val sharingModeGranted: Boolean = false,
    val framesPerBurst: Int = 0,
    val bufferSizeFrames: Int = 0,
    val bufferCapacityFrames: Int = 0,
    val bufferSizeMs: Double = 0.0,
    val burstMs: Double = 0.0,
    val halReportedLatencyMs: Double? = null,
    val latencyConfidence: Double = 0.0,
    val lowLatencyPathDenied: Boolean = false,
    val timestampAvailable: Boolean = false,
    val timestampStable: Boolean = false,
    val highLatencyRoute: Boolean = false,
) {
    fun hasValidHalLatency(): Boolean =
        halReportedLatencyMs != null &&
            halReportedLatencyMs.isFinite() &&
            halReportedLatencyMs >= 0.0
}
