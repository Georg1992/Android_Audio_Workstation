package com.georgv.audioworkstation.ui.screens.projects

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.georgv.audioworkstation.ui.components.timelinePlayheadClampedPositionMs

internal class ProjectPlayheadSeekCoordinator(
    private val scope: CoroutineScope,
    private val playheadPositionMs: MutableStateFlow<Long>,
    private val playheadTransport: PlayheadTransportController,
    private val playbackSession: PlaybackSessionController,
    private val transportController: ProjectTransportController,
    private val recordingSession: RecordingSessionController,
    private val scopePlaybackCoordinator: ScopePlaybackCoordinator,
    private val projectId: () -> String?,
    private val selectedTrackIds: () -> Set<String>,
) {
    fun resetWhenProjectChanges() {
        playheadTransport.resetWhenProjectChanges()
    }

    fun setPlayheadPositionMs(positionMs: Long, timelineBaseDurationMs: Long) {
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        if (!playheadTransport.canScrubPlayhead()) return
        playheadPositionMs.value = timelinePlayheadClampedPositionMs(positionMs, timelineBaseDurationMs)
    }

    fun onPlayheadScrubStarted() {
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        if (playheadTransport.phase.value != TransportPlaybackPhase.Playing) return
        playheadTransport.beginPlaybackSeekDrag()
        scope.launch { transportController.pauseEnginePreservingSession() }
    }

    fun onPlayheadScrubPreviewPosition(positionMs: Long, timelineDurationMs: Long) {
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        val clamped = timelinePlayheadClampedPositionMs(positionMs, timelineDurationMs)
        if (playheadTransport.isPlaybackSeekDragActive()) {
            playheadTransport.setPlayheadDuringSeekDrag(clamped, timelineDurationMs)
            return
        }
        if (!playheadTransport.canScrubPlayhead()) return
        playheadPositionMs.value = clamped
    }

    fun onPlayheadScrubCommittedPosition(positionMs: Long, timelineDurationMs: Long) {
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        val clamped = timelinePlayheadClampedPositionMs(positionMs, timelineDurationMs)
        if (playheadTransport.isPlaybackSeekDragActive()) {
            playheadTransport.setPlayheadDuringSeekDrag(clamped, timelineDurationMs)
            scope.launch { completePlaybackSeekDragScrub() }
            return
        }
        if (!playheadTransport.canScrubPlayhead()) return
        playheadPositionMs.value = clamped
    }

    fun onPlayheadScrubCancelled() {
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        abortPlaybackSeekDragToPaused()
    }

    suspend fun completePlaybackSeekDragScrub() {
        if (!playheadTransport.endPlaybackSeekDragAndConsumeResume()) return
        if (!restartPlaybackFromPlayheadAfterSeekDrag()) {
            abortPlaybackSeekDragToPaused()
        }
    }

    suspend fun restartPlaybackFromPlayheadAfterSeekDrag(): Boolean {
        if (!playbackSession.hasActivePlaybackSession()) return false
        if (selectedTrackIds().isEmpty()) return false
        return scopePlaybackCoordinator.rebuildPlaybackAtCurrentTransport(selectedTrackIds())
    }

    fun abortPlaybackSeekDragToPaused() {
        if (playheadTransport.isPlaybackSeekDragActive()) {
            playheadTransport.endPlaybackSeekDragAndConsumeResume()
        }
        if (playheadTransport.phase.value != TransportPlaybackPhase.Playing) return
        playheadTransport.enterPaused()
        scope.launch { transportController.pausePlayback() }
    }
}
