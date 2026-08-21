package com.georgv.audioworkstation.core.audio

import android.util.Log
import com.georgv.audioworkstation.core.audio.capability.ResolvedAudioCapability
import com.georgv.audioworkstation.core.diagnostics.AudioSyncLogConfig

/**
 * Debug visibility for device latency profile at normal overdub record start.
 *
 * Filter logcat: `adb logcat -s AudioSyncDiag`
 */
object RecordingLatencyCalibrationLog {
    private const val LOG_TAG = "AudioSyncDiag"

    const val PROFILE_STATE_PREFIX = "[LATENCY_PROFILE_STATE]"
    const val ROUTE_UNCALIBRATED_PREFIX = "[LATENCY_ROUTE_UNCALIBRATED]"
    const val ROUTE_CALIBRATED_PREFIX = "[LATENCY_ROUTE_CALIBRATED]"

    const val UNCALIBRATED_DETAIL =
        "Recording uses raw transport placement. Run sync calibration to populate device profile."
    const val CALIBRATED_DETAIL = "Device latency profile has measured round-trip data."

    fun profileStateLogMessage(capability: ResolvedAudioCapability): String =
        "$PROFILE_STATE_PREFIX " +
            "routeKey=${capability.routeKey} " +
            "profileState=${capability.profileState} " +
            "confidence=${capability.confidence} " +
            "measuredRoundTripMs=${capability.roundTripMs} " +
            "hasMeasuredRoundTrip=${hasMeasuredRoundTrip(capability)}"

    fun routeUncalibratedLogMessage(): String =
        "$ROUTE_UNCALIBRATED_PREFIX $UNCALIBRATED_DETAIL"

    fun routeCalibratedLogMessage(): String =
        "$ROUTE_CALIBRATED_PREFIX $CALIBRATED_DETAIL"

    fun logNormalOverdubStart(capability: ResolvedAudioCapability) {
        if (AudioSyncLogConfig.clockValidationMode) {
            return
        }
        logLine(profileStateLogMessage(capability))
        if (hasMeasuredRoundTrip(capability)) {
            logLine(routeCalibratedLogMessage())
        } else {
            logLine(routeUncalibratedLogMessage())
        }
    }

    private fun hasMeasuredRoundTrip(capability: ResolvedAudioCapability): Boolean {
        val roundTripMs = capability.roundTripMs
        return roundTripMs != null && roundTripMs.isFinite() && roundTripMs >= 0.0
    }

    private fun logLine(message: String) {
        runCatching {
            Log.i(LOG_TAG, message)
        }
    }
}
