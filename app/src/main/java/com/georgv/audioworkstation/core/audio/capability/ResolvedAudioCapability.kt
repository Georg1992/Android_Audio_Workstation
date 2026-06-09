package com.georgv.audioworkstation.core.audio.capability

data class ResolvedAudioCapability(
    val profile: DeviceAudioCapabilityProfile?,
    val profileId: String,
    val routeKey: String,
    val sampleRate: Int,
    val outputLatencyMs: Double?,
    val inputCaptureDelayMs: Double?,
    val inputHalLatencyMs: Double?,
    val roundTripMs: Double?,
    val jitterMs: Double?,
    val confidence: Double,
    val lowLatencyOutputPathGranted: Boolean,
    val lowLatencyInputPathGranted: Boolean,
    val profileState: CapabilityProfileState,
    val routeUnchanged: Boolean,
    val validation: CapabilityValidationFlags,
    val warnings: List<String>,
    val dataComplete: Boolean,
)
