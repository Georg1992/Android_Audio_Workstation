package com.georgv.audioworkstation.ui.diagnostics

import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Lightweight composition / remember timing for cold-start nav investigation (debug only). */
object ComposeTimingDiagnostics {
    const val TAG = "ComposeTimingDiag"

    var loggingEnabled: Boolean = false

    fun log(event: String, detail: String = "") {
        if (!loggingEnabled) return
        if (detail.isEmpty()) {
            Log.d(TAG, event)
        } else {
            Log.d(TAG, "$event $detail")
        }
    }

    inline fun <T> traceSection(section: String, block: () -> T): T {
        Trace.beginSection(section)
        val startMs = if (loggingEnabled) SystemClock.uptimeMillis() else 0L
        try {
            return block()
        } finally {
            Trace.endSection()
            if (loggingEnabled) {
                val elapsedMs = SystemClock.uptimeMillis() - startMs
                log("trace end", "section=$section elapsedMs=$elapsedMs")
            }
        }
    }

    /** Logs first commit and dispose for a composable scope (once per [instanceKey]). */
    @Composable
    fun TrackComposition(scope: String, instanceKey: String = scope) {
        if (!loggingEnabled) return

        val composeStartMs = remember(instanceKey) { SystemClock.uptimeMillis() }
        var firstCommitLogged by remember(instanceKey) { mutableStateOf(false) }

        DisposableEffect(instanceKey) {
            log("composition start", "scope=$scope key=$instanceKey")
            onDispose {
                log("composition dispose", "scope=$scope key=$instanceKey")
            }
        }

        SideEffect {
            if (!firstCommitLogged) {
                firstCommitLogged = true
                val elapsedMs = SystemClock.uptimeMillis() - composeStartMs
                log("composition first commit", "scope=$scope key=$instanceKey elapsedMs=$elapsedMs")
            }
        }
    }

    /** Wraps [remember] calculation with a systrace section and elapsed log. */
    @Composable
    inline fun <T> rememberTraced(
        traceLabel: String,
        vararg keys: Any?,
        crossinline calculation: () -> T,
    ): T {
        return remember(*keys) {
            traceSection("remember:$traceLabel", calculation)
        }
    }
}
