package com.georgv.audioworkstation.core.audio.latency

fun latencyFramesFromNs(latencyNs: Long, sampleRateHz: Int): Long {
    if (latencyNs <= 0L || sampleRateHz <= 0) return 0L
    return latencyNs * sampleRateHz / LatencyTimeConstants.NanosecondsPerSecond
}

fun effectiveOutputLatencyNs(
    liveLatencyNs: Long,
    liveLatencyValid: Boolean,
    sessionConfiguredLatencyNs: Long,
): Long =
    when {
        liveLatencyValid && liveLatencyNs > 0L -> liveLatencyNs
        sessionConfiguredLatencyNs > 0L -> sessionConfiguredLatencyNs
        else -> 0L
    }

fun mixTransportFrameAtCallback(
    callbackMonotonicNs: Long,
    effectiveLatencyNs: Long,
    anchor: TransportClockAnchor,
    offlineTransportFrameFallback: Long,
    sampleRateHz: Int,
): Long {
    if (effectiveLatencyNs <= 0L) {
        return if (anchor.isValid) anchor.transportFrameAt(callbackMonotonicNs) else offlineTransportFrameFallback
    }
    if (anchor.isValid) {
        return anchor.transportFrameAt(callbackMonotonicNs + effectiveLatencyNs)
    }
    val rateHz = if (sampleRateHz > 0) sampleRateHz else anchor.sampleRateHz
    return offlineTransportFrameFallback + latencyFramesFromNs(effectiveLatencyNs, rateHz)
}
