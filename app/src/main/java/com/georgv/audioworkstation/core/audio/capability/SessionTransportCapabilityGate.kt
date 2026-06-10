package com.georgv.audioworkstation.core.audio.capability

import com.georgv.audioworkstation.core.audio.latency.latencyMsToNs
import com.georgv.audioworkstation.engine.NativeEngine
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for live-session transport latency: resolve [ResolvedAudioCapability]
 * from [AudioCapabilityProfileResolver], derive session hints, push to native once.
 *
 * Read-only UI summaries stay on [DeviceLatencyReadApi]; post-session measurement on
 * [AudioCapabilityProfileCollector] / [RecordingSessionLatencyAudit].
 */
interface SessionTransportCapabilityGate {
    /** Resolve profile for [sampleRate], apply session latencies to native, cache for idempotent ensure. */
    suspend fun prepareForLiveSession(sampleRate: Int): ResolvedAudioCapability

    /** Apply cached profile when already prepared; otherwise load cache-only (no disk resolve). */
    fun ensurePreparedForSampleRate(sampleRate: Int)

    fun lastPreparedCapability(): ResolvedAudioCapability?
}

@Singleton
class DefaultSessionTransportCapabilityGate @Inject constructor(
    private val resolver: AudioCapabilityProfileResolver,
    private val nativeEngine: NativeEngine,
) : SessionTransportCapabilityGate {
    private val lastPrepared = AtomicReference<ResolvedAudioCapability?>(null)

    override suspend fun prepareForLiveSession(sampleRate: Int): ResolvedAudioCapability {
        val resolved = resolver.resolve(sampleRate)
        applyToNative(resolved)
        lastPrepared.set(resolved)
        return resolved
    }

    override fun ensurePreparedForSampleRate(sampleRate: Int) {
        lastPrepared.get()?.takeIf { it.sampleRate == sampleRate }?.let { cached ->
            applyToNative(cached)
            return
        }
        applyCachedOrClear(sampleRate)
    }

    override fun lastPreparedCapability(): ResolvedAudioCapability? = lastPrepared.get()

    private fun applyCachedOrClear(sampleRate: Int) {
        val cached =
            DeviceAudioCapabilityProfileCache.current()?.takeIf { it.sampleRate == sampleRate }
        if (cached == null) {
            clearNativeSessionLatencies()
            lastPrepared.set(null)
            return
        }
        val resolved = resolver.toResolvedCapability(cached)
        applyToNative(resolved)
        lastPrepared.set(resolved)
    }

    private fun applyToNative(resolved: ResolvedAudioCapability) {
        val inputMs = resolved.sessionInputLatencyMs()
        val outputMs = resolved.sessionOutputLatencyMs()
        nativeEngine.setSessionTransportLatenciesNs(
            latencyMsToNs(inputMs),
            latencyMsToNs(outputMs),
        )
        SessionTransportCapabilityLog.logApplied(
            routeKey = resolved.routeKey,
            profileState = resolved.profileState,
            inputLatencyMs = inputMs,
            outputLatencyMs = outputMs,
        )
    }

    private fun clearNativeSessionLatencies() {
        nativeEngine.setSessionTransportLatenciesNs(0L, 0L)
        SessionTransportCapabilityLog.logCleared()
    }
}

fun ResolvedAudioCapability.sessionInputLatencyMs(): Double =
    inputCaptureDelayMs?.takeIf { it.isFinite() && it > 0.0 }
        ?: inputHalLatencyMs?.takeIf { it.isFinite() && it > 0.0 }
        ?: 0.0

fun ResolvedAudioCapability.sessionOutputLatencyMs(): Double =
    outputLatencyMs?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

internal object SessionTransportCapabilityLog {
    private const val TAG = "AudioSyncDiag"

    fun logApplied(
        routeKey: String,
        profileState: CapabilityProfileState,
        inputLatencyMs: Double,
        outputLatencyMs: Double,
    ) {
        android.util.Log.i(
            TAG,
            "[SESSION_TRANSPORT_CAPABILITY] " +
                "routeKey=$routeKey " +
                "profileState=$profileState " +
                "inputLatencyMs=${formatMs(inputLatencyMs)} " +
                "outputLatencyMs=${formatMs(outputLatencyMs)}",
        )
    }

    fun logCleared() {
        android.util.Log.i(TAG, "[SESSION_TRANSPORT_CAPABILITY] cleared session latencies (no profile)")
    }

    private fun formatMs(value: Double): String =
        if (value.isFinite() && value >= 0.0) {
            String.format(java.util.Locale.US, "%.3f", value)
        } else {
            "n/a"
        }
}
