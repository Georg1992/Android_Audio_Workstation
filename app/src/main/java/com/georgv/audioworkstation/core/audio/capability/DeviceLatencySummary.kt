package com.georgv.audioworkstation.core.audio.capability

import com.georgv.audioworkstation.core.audio.capability.audit.CapabilityCompletenessResult

data class DeviceLatencySummary(
    val routeKey: String,
    val sampleRate: Int,
    val profileState: CapabilityProfileState,
    val outputMedianMs: Double?,
    val outputP95Ms: Double?,
    val inputCaptureMedianMs: Double?,
    val roundTripMedianMs: Double?,
    val jitterMedianMs: Double?,
    val appAddedOutputP95Ms: Double?,
    val appAddedInputP95Ms: Double?,
    val lowLatencyOutputGranted: Boolean,
    val lowLatencyInputGranted: Boolean,
    val bestKnownBackend: String,
    val highLatencyRoute: Boolean,
    val dataConfidence: Double,
    val missingData: List<String>,
    val warnings: List<String>,
    val dataComplete: Boolean,
    val profileId: String,
    val backendInventory: List<BackendCapabilitySnapshot>,
    val completeness: CapabilityCompletenessResult?,
)
