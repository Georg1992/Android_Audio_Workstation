package com.georgv.audioworkstation.core.audio.capability

data class LatencyMeasurementSample(
    val valueMs: Double,
    val measuredAt: Long = System.currentTimeMillis(),
)
