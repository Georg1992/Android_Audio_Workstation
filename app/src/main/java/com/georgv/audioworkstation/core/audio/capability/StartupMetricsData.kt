package com.georgv.audioworkstation.core.audio.capability

/**
 * Session startup timing — explicitly NOT capture latency.
 * Stored for investigation only; never used for compensation math.
 */
data class StartupMetricsData(
    val armToFirstInputMs: Long? = null,
    val armToFirstAudibleMs: Long? = null,
    val firstInputToFirstAudibleMs: Long? = null,
    val startupMetricsUpdatedAt: Long = 0L,
)
