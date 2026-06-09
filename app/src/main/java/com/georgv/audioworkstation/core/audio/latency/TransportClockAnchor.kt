package com.georgv.audioworkstation.core.audio.latency

/** JVM mirror of native [transport_clock::TransportClockAnchor] for mapping tests and render-ahead math. */
data class TransportClockAnchor(
    val transportStartFrame: Long,
    val monotonicStartNs: Long,
    val sampleRateHz: Int,
) {
    val isValid: Boolean
        get() = sampleRateHz > 0 && monotonicStartNs > 0L

    fun transportFrameAt(monotonicNs: Long): Long {
        if (!isValid) return transportStartFrame
        val elapsedNs = monotonicNs - monotonicStartNs
        val frameDelta = elapsedNs * sampleRateHz / NsPerSecond
        return transportStartFrame + frameDelta
    }

    fun transportMsAt(monotonicNs: Long): Long {
        if (sampleRateHz <= 0) return 0L
        val frame = transportFrameAt(monotonicNs) - transportStartFrame
        return frame.coerceAtLeast(0L) * 1000L / sampleRateHz
    }

    fun monotonicNsForTransportFrame(frame: Long): Long {
        if (!isValid) return monotonicStartNs
        val frameDelta = frame - transportStartFrame
        val nsDelta = frameDelta * NsPerSecond / sampleRateHz
        return monotonicStartNs + nsDelta
    }

    fun monotonicNsForTransportMs(transportMsFromStartFrame: Long): Long {
        if (sampleRateHz <= 0) return monotonicStartNs
        val frameDelta = transportMsFromStartFrame.coerceAtLeast(0L) * sampleRateHz / 1000L
        return monotonicNsForTransportFrame(transportStartFrame + frameDelta)
    }

    private companion object {
        const val NsPerSecond = LatencyTimeConstants.NanosecondsPerSecond
    }
}
