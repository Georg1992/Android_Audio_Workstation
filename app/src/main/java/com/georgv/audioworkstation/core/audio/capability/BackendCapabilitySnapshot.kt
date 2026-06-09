package com.georgv.audioworkstation.core.audio.capability

data class BackendCapabilitySnapshot(
    val audioApi: String,
    val direction: String,
    val performanceMode: String,
    val performanceModeGranted: Boolean,
    val sharingMode: String,
    val sharingModeGranted: Boolean,
    val framesPerBurst: Int,
    val bufferSizeFrames: Int,
    val halReportedLatencyMs: Double?,
    val latencyAvailable: Boolean,
    val measuredLatencyMs: Double?,
    val testedAt: Long,
)
