package com.georgv.audioworkstation.ui.screens.projects

import android.util.Log
import com.georgv.audioworkstation.ui.navigation.NavTransitionDiagnostics

/** Temporary debug diagnostics for Project screen composition (enabled in debug builds). */
object ProjectDiagnostics {
    const val TAG = "ProjectDiag"

    var loggingEnabled: Boolean = false

    fun logShellRenderedImmediately(projectId: String) {
        if (!loggingEnabled) return
        val sinceNavMs = NavTransitionDiagnostics.millisSinceLastTransitionEnterStart()
        val sinceNavLabel =
            if (sinceNavMs >= 0L) " sinceNavEnterMs=$sinceNavMs" else ""
        Log.d(TAG, "Project shell rendered immediately projectId=$projectId$sinceNavLabel")
    }

    fun logBindStarted(projectId: String) {
        if (!loggingEnabled) return
        Log.d(TAG, "Project bind started projectId=$projectId")
    }

    fun logContentGateOpened(projectId: String, delayMs: Long) {
        if (!loggingEnabled) return
        Log.d(TAG, "Project content gate opened projectId=$projectId delayMs=$delayMs")
    }

    fun logHeavyWorkspaceRendered(projectId: String, trackCount: Int) {
        if (!loggingEnabled) return
        Log.d(TAG, "ProjectHeavyWorkspace rendered projectId=$projectId trackCount=$trackCount")
    }
}
