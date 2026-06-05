package com.georgv.audioworkstation.ui.screens.library

import android.util.Log
import com.georgv.audioworkstation.core.ui.DataAvailability
import com.georgv.audioworkstation.core.ui.ScreenState
import com.georgv.audioworkstation.core.ui.isContentEmpty

/** Temporary debug diagnostics for Library screen state (enabled in debug builds). */
object LibraryDiagnostics {
    const val TAG = "LibraryDiag"

    var loggingEnabled: Boolean = false

    fun logProjectsFirstEmission(count: Int) {
        if (!loggingEnabled) return
        Log.d(TAG, "repository projects first emission received count=$count")
    }

    fun logStateEmitted(state: ScreenState<LibraryContent>) {
        if (!loggingEnabled) return
        val phase = when (state.availability) {
            DataAvailability.Pending -> "Pending"
            DataAvailability.Ready -> "Ready"
        }
        Log.d(
            TAG,
            "Library state emitted phase=$phase count=${state.content.projects.size} " +
                "refreshing=${state.isRefreshing}",
        )
    }

    fun logRendered(state: ScreenState<LibraryContent>) {
        if (!loggingEnabled) return
        val uiPhase =
            when {
                state.isInitialLoad -> "Loading"
                state.isContentEmpty { it.projects.isEmpty() } -> "Empty"
                else -> "Ready"
            }
        Log.d(TAG, "Library rendered phase=$uiPhase count=${state.content.projects.size}")
    }
}
