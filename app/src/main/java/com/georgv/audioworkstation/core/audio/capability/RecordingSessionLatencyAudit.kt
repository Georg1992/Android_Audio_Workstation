package com.georgv.audioworkstation.core.audio.capability

import com.georgv.audioworkstation.core.audio.latency.AudioLivePathType
import com.georgv.audioworkstation.core.audio.latency.LiveSessionLatencySnapshot
import com.georgv.audioworkstation.engine.NativeEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists stream capability + stabilization audit after a live overdub session ends.
 * Read-only: does not change compensation or transport placement.
 */
fun interface LiveOverdubLatencySessionRecorder {
    suspend fun recordLiveOverdubSessionEnd(
        sampleRateHz: Int,
        capture: LiveSessionLatencySnapshot,
    )
}

@Singleton
class RecordingSessionLatencyAudit @Inject constructor(
    private val capabilityProfileCollector: AudioCapabilityProfileCollector,
    private val nativeEngine: NativeEngine,
) : LiveOverdubLatencySessionRecorder {
    override suspend fun recordLiveOverdubSessionEnd(
        sampleRateHz: Int,
        capture: LiveSessionLatencySnapshot,
    ) {
        capabilityProfileCollector.collectFromLiveSessionEnd(
            sampleRateHz = sampleRateHz,
            pathType = AudioLivePathType.OVERDUB,
            outputProbe = capture.outputProbe,
            inputProbe = capture.inputProbe,
            timings = nativeEngine.playbackSessionTimings(),
        )
    }
}
