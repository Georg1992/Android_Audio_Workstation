package com.georgv.audioworkstation.core.diagnostics

import android.os.SystemClock

/**
 * Kotlin-side JNI boundary stamps for playback startup call-chain reconstruction.
 * Native [PLAYBACK_STARTUP_BREAKDOWN] milestones use the same playback_arm anchor.
 */
object PlaybackStartupTrace {
    fun logJniBoundary(path: String, phase: String) {
        if (!AudioSyncLogConfig.detailedStartupLogsEnabled) {
            return
        }
        AudioSyncDiag.log(
            AudioSyncDiag.Prefix.PLAYBACK_STARTUP_BREAKDOWN,
            "kotlin path=$path phase=$phase uptimeMs=${SystemClock.uptimeMillis()} " +
                "thread=${Thread.currentThread().name}",
        )
    }
}
