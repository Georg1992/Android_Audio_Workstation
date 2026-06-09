package com.georgv.audioworkstation.core.audio.capability

import android.util.Log

internal object AudioCapabilityProfileLog {
    private const val TAG = "AudioSyncDiag"

    fun logUpdated(profile: DeviceAudioCapabilityProfile) {
        val outputMs = DeviceAudioCapabilityClassifier.effectiveOutputLatencyMs(profile)
        val inputCaptureMs = profile.calibration.estimatedTrueCaptureDelayMs
        Log.i(
            TAG,
            "[AUDIO_CAPABILITY_PROFILE_UPDATED] " +
                "profileId=${profile.profileId} " +
                "routeKey=${profile.routeKey} " +
                "sampleRate=${profile.sampleRate} " +
                "outputLatencyMs=${formatMs(outputMs)} " +
                "inputCaptureDelayMs=${formatMs(inputCaptureMs)} " +
                "roundTripMs=${formatMs(profile.calibration.measuredRoundTripMs)} " +
                "jitterMs=${formatMs(profile.calibration.measuredJitterMs)} " +
                "confidence=${profile.overallConfidence()} " +
                "outputTier=${profile.derived.outputTier} " +
                "inputTier=${profile.derived.inputTier} " +
                "profileState=${profile.derived.profileState} " +
                "lowLatencyPathGranted=${profile.output.performanceModeGranted}",
        )
    }

    fun logLoaded(profile: DeviceAudioCapabilityProfile) {
        Log.i(
            TAG,
            "[AUDIO_CAPABILITY_PROFILE_LOADED] " +
                "profileId=${profile.profileId} " +
                "routeKey=${profile.routeKey} " +
                "sampleRate=${profile.sampleRate} " +
                "updatedAt=${profile.updatedAt} " +
                "confidence=${profile.overallConfidence()} " +
                "profileState=${profile.derived.profileState}",
        )
    }

    fun logMissing(profileId: String, routeKey: String, sampleRate: Int) {
        Log.i(
            TAG,
            "[AUDIO_CAPABILITY_PROFILE_MISSING] " +
                "profileId=$profileId " +
                "routeKey=$routeKey " +
                "sampleRate=$sampleRate",
        )
    }

    fun logLatencySnapshot(resolved: ResolvedAudioCapability) {
        Log.i(
            TAG,
            "[AUDIO_LATENCY_SNAPSHOT] " +
                "routeKey=${resolved.routeKey} " +
                "profileState=${resolved.profileState} " +
                "outputLatencyMs=${formatMs(resolved.outputLatencyMs)} " +
                "inputCaptureDelayMs=${formatMs(resolved.inputCaptureDelayMs)} " +
                "roundTripMs=${formatMs(resolved.roundTripMs)} " +
                "jitterMs=${formatMs(resolved.jitterMs)} " +
                "confidence=${resolved.confidence}",
        )
    }

    private fun formatMs(value: Double?): String =
        if (value != null && value.isFinite() && value >= 0.0) {
            String.format("%.3f", value)
        } else {
            "n/a"
        }
}
