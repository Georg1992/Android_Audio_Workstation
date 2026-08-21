package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.audio.capability.ResolvedAudioCapability
import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import com.georgv.audioworkstation.core.audio.latency.LatencyTimeConstants

/**
 * Single source of truth for multi-playback transport ↔ UI sync.
 *
 * Output latency for playhead and seek uses **live HAL only** when valid — matching native
 * [output_render_ahead::effectiveOutputLatencyNs]. Session profile latencies are transport
 * hints for capture placement, not UI render-ahead fallback.
 */
object PlaybackTransportSync {
    const val LiveOutputLatencyUnsetNs = -1L

    fun requirePreparedCapability(gate: SessionTransportCapabilityGate): ResolvedAudioCapability =
        checkNotNull(gate.lastPreparedCapability()) {
            "Session transport capability not prepared for live audio"
        }

    /** Live HAL ms when valid; otherwise 0 (no render-ahead on either side). */
    fun effectiveOutputLatencyMsForUiSync(meter: MeterPort): Double {
        val liveNs = meter.liveOutputLatencyNs()
        if (liveNs <= LiveOutputLatencyUnsetNs) {
            return 0.0
        }
        return liveNs / LatencyTimeConstants.NanosecondsPerMillisecond
    }

    fun mixTransportMs(meter: MeterPort, audiblePlayheadMs: AudibleMs): MixTransportMs =
        mixTransportMs(audiblePlayheadMs, effectiveOutputLatencyMsForUiSync(meter))

    fun audiblePlayheadMs(meter: MeterPort, mixTransportMs: MixTransportMs): AudibleMs =
        audiblePlayheadMs(mixTransportMs, effectiveOutputLatencyMsForUiSync(meter))

    fun mixTransportMs(audiblePlayheadMs: AudibleMs, outputLatencyMs: Double): MixTransportMs =
        MixTransportMs(
            audiblePlayheadToMixTransportMs(audiblePlayheadMs.value, outputLatencyMs),
        )

    fun audiblePlayheadMs(mixTransportMs: MixTransportMs, outputLatencyMs: Double): AudibleMs =
        AudibleMs(
            mixTransportToAudiblePlayheadMs(mixTransportMs.value, outputLatencyMs),
        )

    fun mixTransportMsFromRaw(rawMixTransportMs: Long): MixTransportMs =
        MixTransportMs(rawMixTransportMs.coerceAtLeast(0L))

    fun audiblePlayheadMsFromRaw(meter: MeterPort, rawMixTransportMs: Long): AudibleMs =
        audiblePlayheadMs(meter, mixTransportMsFromRaw(rawMixTransportMs))
}

/** Converts audible start intent to native mix transport for engine arm/seek. */
fun MultiPlaybackSpec.withAudibleStartPositionMs(
    audibleStartMs: AudibleMs,
    outputLatencyMs: Double,
): MultiPlaybackSpec =
    copy(startPositionMs = PlaybackTransportSync.mixTransportMs(audibleStartMs, outputLatencyMs).value)

/** Convenience for call sites still holding raw audible ms. */
fun MultiPlaybackSpec.withAudibleStartPositionMs(
    audibleStartMs: Long,
    outputLatencyMs: Double,
): MultiPlaybackSpec =
    withAudibleStartPositionMs(AudibleMs(audibleStartMs.coerceAtLeast(0L)), outputLatencyMs)
