package com.georgv.audioworkstation.core.audio.latency

import kotlin.math.round

fun latencyMsToNs(latencyMs: Double): Long =
    if (latencyMs.isFinite() && latencyMs > 0.0) {
        round(latencyMs * LatencyTimeConstants.NanosecondsPerMillisecond).toLong()
    } else {
        0L
    }
