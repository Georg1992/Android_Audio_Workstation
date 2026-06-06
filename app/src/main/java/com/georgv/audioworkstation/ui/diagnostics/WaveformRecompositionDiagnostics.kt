package com.georgv.audioworkstation.ui.diagnostics

import android.util.Log
import com.georgv.audioworkstation.ui.components.WaveformState
import com.georgv.audioworkstation.ui.screens.projects.ProjectUiState
import com.georgv.audioworkstation.ui.screens.projects.ProjectDiagnostics.waveformStateDiagnosticLabel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only counters for waveform Loading churn investigation.
 * Enable via [loggingEnabled] (debug builds in [com.georgv.audioworkstation.MyApplication]).
 */
object WaveformRecompositionDiagnostics {
    const val TAG = "WaveformRecompDiag"

    var loggingEnabled: Boolean = false

    private val loadingEmissionCount = ConcurrentHashMap<String, AtomicInteger>()
    private val loadingToLoadingCount = ConcurrentHashMap<String, AtomicInteger>()
    private val mapReplacementCount = ConcurrentHashMap<String, AtomicInteger>()
    private val heavyWorkspaceRecompositionCount = AtomicInteger()
    private val heavyWorkspaceLaunchedEffectCount = AtomicInteger()
    private val laneRecompositionCount = ConcurrentHashMap<String, AtomicInteger>()

    fun resetSession() {
        loadingEmissionCount.clear()
        loadingToLoadingCount.clear()
        mapReplacementCount.clear()
        heavyWorkspaceRecompositionCount.set(0)
        heavyWorkspaceLaunchedEffectCount.set(0)
        laneRecompositionCount.clear()
    }

    fun logCoordinatorMapAssignment(
        source: String,
        previous: Map<String, WaveformState>,
        next: Map<String, WaveformState>,
    ) {
        if (!loggingEnabled) return
        val prevIdentity = System.identityHashCode(previous)
        val nextIdentity = System.identityHashCode(next)
        val sameIdentity = previous === next
        val sameContent = previous == next

        next.forEach { (trackId, newState) ->
            val prevState = previous[trackId]
            if (prevState == newState && prevState != null) return@forEach
            val prevLabel = prevState?.let { waveformStateLabel(it) } ?: "absent"
            val newLabel = waveformStateLabel(newState)
            Log.d(
                TAG,
                "waveform map assign source=$source trackId=$trackId " +
                    "previousState=$prevLabel newState=$newLabel " +
                    "mapIdentity=$prevIdentity->$nextIdentity sameIdentity=$sameIdentity sameContent=$sameContent",
            )
            if (newLabel == "loading") {
                loadingEmissionCount.computeIfAbsent(trackId) { AtomicInteger() }.incrementAndGet()
                if (prevLabel == "loading") {
                    loadingToLoadingCount.computeIfAbsent(trackId) { AtomicInteger() }.incrementAndGet()
                    Log.w(TAG, "Loading->Loading emission trackId=$trackId source=$source")
                }
            }
            if (!sameIdentity) {
                mapReplacementCount.computeIfAbsent(trackId) { AtomicInteger() }.incrementAndGet()
            }
        }

        previous.keys.subtract(next.keys).forEach { removedId ->
            Log.d(
                TAG,
                "waveform map assign source=$source trackId=$removedId previousState=" +
                    "${previous[removedId]?.let { waveformStateLabel(it) }} newState=removed " +
                    "mapIdentity=$prevIdentity->$nextIdentity",
            )
        }
    }

    fun assignWaveformStates(
        flow: MutableStateFlow<Map<String, WaveformState>>,
        source: String,
        next: Map<String, WaveformState>,
    ) {
        if (loggingEnabled) {
            logCoordinatorMapAssignment(source, flow.value, next)
        }
        flow.value = next
    }

    fun logMergeWaveformStates(
        base: Map<String, WaveformState>,
        merged: Map<String, WaveformState>,
        reason: String,
    ) {
        if (!loggingEnabled) return
        Log.d(
            TAG,
            "mergeWaveformStates reason=$reason baseIdentity=${System.identityHashCode(base)} " +
                "mergedIdentity=${System.identityHashCode(merged)} sameInstance=${base === merged} " +
                "sameContent=${base == merged}",
        )
    }

    fun logProjectScreenSnapshotEmission(
        waveformStates: Map<String, WaveformState>,
        recordingInputLevel: Float,
        importUiSize: Int,
    ) {
        if (!loggingEnabled) return
        Log.d(
            TAG,
            "projectScreenSnapshot emission waveformMapIdentity=" +
                "${System.identityHashCode(waveformStates)} recordingInputLevel=$recordingInputLevel " +
                "importUiEntries=$importUiSize",
        )
    }

    fun logStructuralUiStateEmission(previous: ProjectUiState?, next: ProjectUiState) {
        if (!loggingEnabled) return
        if (previous == null) {
            Log.d(TAG, "structuralUiState first emission")
            return
        }
        val changed = structuralUiStateChangeLabels(previous, next)
        if (changed.isNotEmpty()) {
            Log.d(TAG, "structuralUiState emission changed=${changed.joinToString()}")
        }
    }

    fun logUiStateEmission(previous: ProjectUiState?, next: ProjectUiState) {
        if (!loggingEnabled) return
        if (previous == null) {
            Log.d(TAG, "uiState first emission")
            return
        }
        val changed =
            structuralUiStateChangeLabels(previous, next) +
                buildList {
                    if (previous.playheadPositionMs != next.playheadPositionMs) add("playhead")
                    if (previous.recordingInputLevel != next.recordingInputLevel) add("recordingInputLevel")
                    if (previous.masterPeakDbText != next.masterPeakDbText) add("masterPeak")
                    if (previous.masterPeakIndicatorLevel != next.masterPeakIndicatorLevel) add("masterPeakLevel")
                    if (previous.timelineVisibleDurationMs != next.timelineVisibleDurationMs) {
                        add("timelineVisibleDuration")
                    }
                }
        if (changed.isNotEmpty()) {
            Log.d(TAG, "uiState emission changed=${changed.joinToString()}")
        }
    }

    private fun structuralUiStateChangeLabels(previous: ProjectUiState, next: ProjectUiState): List<String> =
        buildList {
            if (previous.waveformStatesByTrackId !== next.waveformStatesByTrackId) {
                val contentSame = previous.waveformStatesByTrackId == next.waveformStatesByTrackId
                add(
                    "waveformMap(identity=${System.identityHashCode(previous.waveformStatesByTrackId)}" +
                        "->${System.identityHashCode(next.waveformStatesByTrackId)} contentSame=$contentSame)",
                )
            }
            if (previous.waveformStatesByTrackId != next.waveformStatesByTrackId) {
                add("waveformMapContent")
            }
            if (previous.transportPlaybackPhase != next.transportPlaybackPhase) add("transportPhase")
            if (previous.tracks != next.tracks) add("tracks")
            if (previous.timelineClipsByTrackId !== next.timelineClipsByTrackId) {
                add("timelineClips(identity)")
            }
            if (previous.timelineClipsByTrackId != next.timelineClipsByTrackId) add("timelineClipsContent")
            if (previous.selectedTrackIds != next.selectedTrackIds) add("selection")
            if (previous.recordingTrackId != next.recordingTrackId) add("recordingTrackId")
            if (previous.playbackSessionActive != next.playbackSessionActive) add("playbackSession")
        }

    fun logHeavyWorkspaceRecomposition(
        projectId: String,
        waveformStates: Map<String, WaveformState>,
    ) {
        if (!loggingEnabled) return
        val count = heavyWorkspaceRecompositionCount.incrementAndGet()
        Log.d(
            TAG,
            "ProjectHeavyWorkspace recomposition #$count projectId=$projectId " +
                "waveformMapIdentity=${System.identityHashCode(waveformStates)}",
        )
    }

    fun logHeavyWorkspaceLaunchedEffect(
        projectId: String,
        trigger: String,
        waveformStates: Map<String, WaveformState>,
    ) {
        if (!loggingEnabled) return
        val count = heavyWorkspaceLaunchedEffectCount.incrementAndGet()
        Log.d(
            TAG,
            "ProjectHeavyWorkspace LaunchedEffect #$count projectId=$projectId trigger=$trigger " +
                "waveformMapIdentity=${System.identityHashCode(waveformStates)} " +
                "states=${waveformStates.entries.joinToString { "${it.key}=${waveformStateLabel(it.value)}" }}",
        )
    }

    fun logTrackTimelineLaneRecomposition(
        trackId: String,
        waveformState: WaveformState,
    ) {
        if (!loggingEnabled) return
        if (waveformState !is WaveformState.Loading && waveformState !is WaveformState.Importing) return
        val count = laneRecompositionCount.computeIfAbsent(trackId) { AtomicInteger() }.incrementAndGet()
        Log.d(
            TAG,
            "TrackTimelineLane recomposition #$count trackId=$trackId " +
                "waveformState=${waveformStateLabel(waveformState)}",
        )
    }

    private val importProgressEmissionCount = ConcurrentHashMap<String, AtomicInteger>()

    fun logImportProgressEmission(trackId: String, progress: Float) {
        if (!loggingEnabled) return
        val count = importProgressEmissionCount.computeIfAbsent(trackId) { AtomicInteger() }.incrementAndGet()
        Log.d(TAG, "import progress emission #$count trackId=$trackId progress=$progress")
    }

    fun logTrackBecameReady(trackId: String) {
        if (!loggingEnabled) return
        Log.i(
            TAG,
            "session summary trackId=$trackId " +
                "loadingEmissions=${loadingEmissionCount[trackId]?.get() ?: 0} " +
                "loadingToLoading=${loadingToLoadingCount[trackId]?.get() ?: 0} " +
                "mapReplacements=${mapReplacementCount[trackId]?.get() ?: 0} " +
                "laneRecompositions=${laneRecompositionCount[trackId]?.get() ?: 0} " +
                "heavyWorkspaceRecompositions=${heavyWorkspaceRecompositionCount.get()} " +
                "heavyWorkspaceLaunchedEffects=${heavyWorkspaceLaunchedEffectCount.get()}",
        )
    }

    private fun waveformStateLabel(state: WaveformState): String = waveformStateDiagnosticLabel(state)
}
