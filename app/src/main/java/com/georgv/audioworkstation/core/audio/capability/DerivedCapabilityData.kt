package com.georgv.audioworkstation.core.audio.capability

data class DerivedCapabilityData(
    val outputTier: LatencyTier = LatencyTier.UNKNOWN,
    val inputTier: LatencyTier = LatencyTier.UNKNOWN,
    val overallLiveLatencyTier: LatencyTier = LatencyTier.UNKNOWN,
    val recommendedBackend: String = "Unspecified",
    val profileState: CapabilityProfileState = CapabilityProfileState.EMPTY,
)
