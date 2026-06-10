package com.georgv.audioworkstation.core.audio

import kotlin.math.roundToLong

/** Mix transport ms → playhead ms aligned with heard output (live HAL latency when valid). */
fun mixTransportToAudiblePlayheadMs(mixTransportMs: Long, outputLatencyMs: Double): Long {
    if (!outputLatencyMs.isFinite() || outputLatencyMs <= 0.0) {
        return mixTransportMs.coerceAtLeast(0L)
    }
    return (mixTransportMs - outputLatencyMs.roundToLong()).coerceAtLeast(0L)
}

/** Audible playhead ms → native mix transport seek/start position. */
fun audiblePlayheadToMixTransportMs(audiblePlayheadMs: Long, outputLatencyMs: Double): Long {
    if (!outputLatencyMs.isFinite() || outputLatencyMs <= 0.0) {
        return audiblePlayheadMs.coerceAtLeast(0L)
    }
    return (audiblePlayheadMs + outputLatencyMs.roundToLong()).coerceAtLeast(0L)
}
