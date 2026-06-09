package com.georgv.audioworkstation.core.audio.capability

import com.georgv.audioworkstation.core.audio.capability.audit.LatencySystemStabilizationAudit
import com.georgv.audioworkstation.core.audio.latency.AudioLivePathType
import com.georgv.audioworkstation.engine.NativeEngine
import com.georgv.audioworkstation.engine.OboeStreamCapabilityProbe
import com.georgv.audioworkstation.engine.PlaybackSessionTimings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioCapabilityProfileCollector @Inject constructor(
    private val nativeEngine: NativeEngine,
    private val resolver: AudioCapabilityProfileResolver,
    private val stabilizationAudit: LatencySystemStabilizationAudit,
) {
    suspend fun collectStreamCapabilities(
        sampleRateHz: Int,
        pathType: AudioLivePathType = AudioLivePathType.PLAYBACK_ONLY,
    ) {
        val outputProbe = nativeEngine.probeOutputStreamCapability()
        val inputProbe = nativeEngine.probeInputStreamCapability()
        resolver.mergeStreamCapabilities(
            sampleRate = sampleRateHz,
            outputProbe = outputProbe,
            inputProbe = inputProbe,
        )
        finishCollection(sampleRateHz, pathType)
    }

    suspend fun collectFromLiveSessionEnd(
        sampleRateHz: Int,
        pathType: AudioLivePathType = AudioLivePathType.OVERDUB,
        outputProbe: OboeStreamCapabilityProbe? = null,
        inputProbe: OboeStreamCapabilityProbe? = null,
        timings: PlaybackSessionTimings? = null,
    ) {
        val resolvedOutputProbe = outputProbe ?: nativeEngine.probeOutputStreamCapability()
        val resolvedInputProbe = inputProbe ?: nativeEngine.probeInputStreamCapability()
        resolver.mergeStreamCapabilities(sampleRateHz, resolvedOutputProbe, resolvedInputProbe)
        resolver.mergeStartupMetrics(sampleRateHz, timings ?: nativeEngine.playbackSessionTimings())
        finishCollection(sampleRateHz, pathType)
    }

    private suspend fun finishCollection(
        sampleRateHz: Int,
        pathType: AudioLivePathType,
    ): ResolvedAudioCapability {
        val resolved = resolver.resolve(sampleRateHz)
        AudioCapabilityProfileLog.logLatencySnapshot(resolved)
        stabilizationAudit.audit(resolved, pathType)
        return resolved
    }
}
