package com.georgv.audioworkstation.core.audio.capability

data class CapabilityValidationFlags(
    val measurementInconsistent: Boolean = false,
    val captureDelayInvalid: Boolean = false,
    val captureDelayUnknown: Boolean = false,
    val outputLatencyInvalid: Boolean = false,
    val roundTripInvalid: Boolean = false,
)
