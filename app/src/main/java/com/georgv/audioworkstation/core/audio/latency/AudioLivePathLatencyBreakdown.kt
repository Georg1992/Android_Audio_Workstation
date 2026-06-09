package com.georgv.audioworkstation.core.audio.latency

/**
 * Path-level startup and latency budget for one live session.
 * All millisecond fields are relative to playback_arm unless noted.
 */
data class AudioLivePathLatencyBreakdown(
    val pathType: AudioLivePathType,
    val routeKey: String,
    val streamOpenMs: Long?,
    val streamStartMs: Long?,
    val firstCallbackMs: Long?,
    val firstInputMs: Long?,
    val firstNonSilentOutputMs: Long?,
    val deferredGateMs: Long?,
    val decoderOpenMs: Long?,
    val prerollMs: Long?,
    val ioPrefetchMs: Long?,
    val appAddedLatencyMs: Long?,
    val estimatedHardwareLatencyMs: Long?,
    val estimatedTotalLiveLatencyMs: Long?,
    val notes: String,
)
