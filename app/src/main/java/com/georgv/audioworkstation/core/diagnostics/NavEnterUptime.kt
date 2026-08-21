package com.georgv.audioworkstation.core.diagnostics

import android.os.SystemClock

/**
 * Compose-free clock for “ms since last navigation enter started”.
 * [com.georgv.audioworkstation.ui.navigation.NavTransitionDiagnostics] writes; session diagnostics read.
 */
object NavEnterUptime {
    @Volatile
    private var lastTransitionEnterStartUptimeMs: Long = 0L

    fun markEnterStart(uptimeMs: Long) {
        lastTransitionEnterStartUptimeMs = uptimeMs
    }

    fun millisSinceLastEnterStart(): Long {
        if (lastTransitionEnterStartUptimeMs == 0L) return -1L
        return SystemClock.uptimeMillis() - lastTransitionEnterStartUptimeMs
    }
}
