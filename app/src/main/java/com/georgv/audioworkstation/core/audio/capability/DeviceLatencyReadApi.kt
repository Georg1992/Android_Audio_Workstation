package com.georgv.audioworkstation.core.audio.capability

import com.georgv.audioworkstation.engine.NativeEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceLatencyReadApi @Inject constructor(
    private val resolver: AudioCapabilityProfileResolver,
    private val nativeEngine: NativeEngine,
) {
    suspend fun currentRouteSummary(sampleRate: Int = 44_100): DeviceLatencySummary {
        val resolved = resolver.resolve(sampleRate)
        return DeviceLatencySummaryBuilder.build(resolved, nativeEngine)
    }

    suspend fun allStoredSummaries(): List<DeviceLatencySummary> =
        resolver.listStoredProfiles()
            .map { profile ->
                val resolved = resolver.toResolvedCapability(profile)
                DeviceLatencySummaryBuilder.build(resolved, nativeEngine)
            }
}
