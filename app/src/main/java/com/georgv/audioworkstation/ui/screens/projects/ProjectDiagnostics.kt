package com.georgv.audioworkstation.ui.screens.projects

import android.util.Log
import com.georgv.audioworkstation.ui.components.WaveformState
import com.georgv.audioworkstation.ui.navigation.NavTransitionDiagnostics

/** Debug diagnostics for Project loading/navigation (enabled in debug builds only). */
object ProjectDiagnostics {
    const val TAG = "ProjectDiag"

    var loggingEnabled: Boolean = false

    fun logShellRendered(projectId: String, phase: String) {
        if (!loggingEnabled) return
        val sinceNavMs = NavTransitionDiagnostics.millisSinceLastTransitionEnterStart()
        val sinceNavLabel =
            if (sinceNavMs >= 0L) " sinceNavEnterMs=$sinceNavMs" else ""
        Log.d(TAG, "Project shell rendered phase=$phase projectId=$projectId$sinceNavLabel")
    }

    fun logLoadingPlaceholderRendered(projectId: String) {
        if (!loggingEnabled) return
        Log.d(TAG, "Project loading placeholder rendered projectId=$projectId")
    }

    fun logNavTransitionGateOpened(projectId: String, delayMs: Long) {
        if (!loggingEnabled) return
        Log.d(TAG, "Project nav transition gate opened projectId=$projectId delayMs=$delayMs")
    }

    fun logDestinationReady(projectId: String) {
        if (!loggingEnabled) return
        Log.d(TAG, "Project destination ready projectId=$projectId")
    }

    fun logLoadingFadeStarted(projectId: String) {
        if (!loggingEnabled) return
        Log.d(TAG, "Project loading fade started projectId=$projectId")
    }

    fun logLoadingFadeFinished(projectId: String) {
        if (!loggingEnabled) return
        Log.d(TAG, "Project loading fade finished projectId=$projectId")
    }

    fun logReadyLayoutMounted(
        projectId: String,
        trackCount: Int,
        showWaveforms: Boolean,
        quick: Boolean,
        navMaxGapMs: Long = NavTransitionDiagnostics.peekMaxFrameGapMs(),
    ) {
        if (!loggingEnabled) return
        Log.d(
            TAG,
            "Project ready layout mounted projectId=$projectId trackCount=$trackCount " +
                "showWaveforms=$showWaveforms quick=$quick navMaxGapMs=${navMaxGapMs}ms",
        )
    }

    fun logTrackWaveformStates(
        projectId: String,
        waveformStatesByTrackId: Map<String, WaveformState>,
        trackIds: List<String>,
    ) {
        if (!loggingEnabled) return
        if (trackIds.isEmpty()) return
        val summary =
            trackIds.joinToString(separator = " ") { trackId ->
                val label =
                    waveformStateDiagnosticLabel(
                        waveformStatesByTrackId[trackId] ?: WaveformState.Loading,
                    )
                "$trackId=$label"
            }
        Log.d(TAG, "Project track waveform states projectId=$projectId $summary")
    }

    fun waveformStateDiagnosticLabel(state: WaveformState): String =
        when (state) {
            is WaveformState.Ready -> "ready"
            WaveformState.Loading,
            is WaveformState.Importing,
            -> "loading"
            else -> "empty"
        }

    fun logQuickProjectWaitingForInitialTrack(projectId: String) {
        if (!loggingEnabled) return
        Log.d(TAG, "QuickProject waitingForInitialTrack projectId=$projectId")
    }

    fun logQuickProjectInitialTrackReady(projectId: String, trackCount: Int) {
        if (!loggingEnabled) return
        Log.d(TAG, "QuickProject initialTrackReady projectId=$projectId trackCount=$trackCount")
    }

    fun logWaveformsEnabled(projectId: String, trackCount: Int) {
        if (!loggingEnabled) return
        Log.d(TAG, "Project waveforms enabled projectId=$projectId trackCount=$trackCount")
    }

    fun logHeavyWorkspaceRendered(projectId: String, trackCount: Int, showWaveforms: Boolean) {
        if (!loggingEnabled) return
        Log.d(
            TAG,
            "ProjectHeavyWorkspace rendered projectId=$projectId trackCount=$trackCount " +
                "showWaveforms=$showWaveforms",
        )
    }

    fun logBindFinished(projectId: String, elapsedMs: Long) {
        if (!loggingEnabled) return
        Log.d(TAG, "Project bind finished projectId=$projectId elapsedMs=$elapsedMs")
    }
}
