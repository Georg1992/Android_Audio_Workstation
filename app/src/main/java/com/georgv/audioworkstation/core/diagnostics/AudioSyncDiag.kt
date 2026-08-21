package com.georgv.audioworkstation.core.diagnostics

import android.util.Log

/**
 * Unified logcat tag for recording/playback synchronization diagnostics.
 *
 * Filter logcat: `adb logcat -s AudioSyncDiag`
 */
object AudioSyncDiag {
    const val TAG = "AudioSyncDiag"

    object Prefix {
        const val LATENCY_BUDGET = "[LATENCY_BUDGET]"
        const val DEVICE_LATENCY_BUDGET = "[DEVICE_LATENCY_BUDGET]"
        const val AUDIO_STREAM_CONFIGURATION = "[AUDIO_STREAM_CONFIGURATION]"
        const val OVERDUB = "[OVERDUB]"
        const val PLAYBACK_AFTER_RECORD = "[PLAYBACK_AFTER_RECORD]"
        const val PLAYBACK_LATENCY = "[PLAYBACK_LATENCY]"
        const val PLAYBACK_STARTUP_BREAKDOWN = "[PLAYBACK_STARTUP_BREAKDOWN]"
        const val OUTPUT_TIMESTAMP = "[OUTPUT_TIMESTAMP]"
        const val INPUT_TIMESTAMP = "[INPUT_TIMESTAMP]"
        const val AUDIO_BUFFER_AUDIT = "[AUDIO_BUFFER_AUDIT]"
        const val AUDIO_LOW_LATENCY_MODE_RESULT = "[AUDIO_LOW_LATENCY_MODE_RESULT]"
        const val AUDIO_EXCLUSIVE_MODE_RESULT = "[AUDIO_EXCLUSIVE_MODE_RESULT]"
        const val AUDIO_APP_ADDED_LATENCY = "[AUDIO_APP_ADDED_LATENCY]"
        const val AUDIO_XRUN_STATUS = "[AUDIO_XRUN_STATUS]"
        const val OVERDUB_STARTUP_TIMING = "[OVERDUB_STARTUP_TIMING]"
    }

    fun formatMessage(prefix: String, message: String): String = "$prefix $message"

    fun shouldLog(prefix: String): Boolean {
        when (prefix) {
            Prefix.LATENCY_BUDGET,
            Prefix.DEVICE_LATENCY_BUDGET,
            Prefix.AUDIO_STREAM_CONFIGURATION,
            -> return true
        }
        if (!AudioSyncLogConfig.clockValidationMode) {
            return true
        }
        return when (prefix) {
            Prefix.PLAYBACK_STARTUP_BREAKDOWN ->
                AudioSyncLogConfig.detailedStartupLogsEnabled
            Prefix.OVERDUB,
            Prefix.PLAYBACK_AFTER_RECORD,
            Prefix.PLAYBACK_LATENCY,
            Prefix.AUDIO_BUFFER_AUDIT,
            Prefix.AUDIO_LOW_LATENCY_MODE_RESULT,
            Prefix.AUDIO_EXCLUSIVE_MODE_RESULT,
            Prefix.AUDIO_APP_ADDED_LATENCY,
            Prefix.AUDIO_XRUN_STATUS,
            Prefix.OVERDUB_STARTUP_TIMING,
            "[LATENCY_PROFILE_LOADED]",
            "[LATENCY_PROFILE_SAVED]",
            "[LATENCY_PROFILE_MISSING]",
            -> false
            else -> true
        }
    }

    fun log(prefix: String, message: String) {
        if (!shouldLog(prefix)) {
            return
        }
        Log.i(TAG, formatMessage(prefix, message))
    }
}
