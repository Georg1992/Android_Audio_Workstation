package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.toMultiPlaybackSpec
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.ProjectTimelineProjection
import com.georgv.audioworkstation.ui.components.WaveformState
import com.georgv.audioworkstation.ui.components.playbackStartAllowedAtPlayhead
import com.georgv.audioworkstation.ui.components.sessionTimelineEndMsForPlayback
import com.georgv.audioworkstation.ui.components.timelinePlayheadClampedPositionMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal class ProjectPlayheadSeekCoordinator(
    private val scope: CoroutineScope,
    private val playheadPositionMs: MutableStateFlow<Long>,
    private val playheadTransport: PlayheadTransportController,
    private val playbackSession: PlaybackSessionController,
    private val transportController: ProjectTransportController,
    private val recordingSession: RecordingSessionController,
    private val projectId: () -> String?,
    private val selectedTrackIds: () -> Set<String>,
    private val loadCurrentProject: suspend (String) -> ProjectEntity?,
    private val selectedPlayableTracks: () -> List<TrackEntity>,
    /** Full project timeline extent (all visible tracks), same as [ProjectTransportCommands.performPlayPressed]. */
    private val timelineTracksForPlayhead: () -> List<TrackEntity>,
    private val timelineProjection: (List<TrackEntity>, Map<String, WaveformState>) -> ProjectTimelineProjection,
    private val waveformStatesByTrackId: () -> Map<String, WaveformState>,
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
        transportController.pauseEnginePreservingSession()
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
        val currentProjectId = projectId() ?: return false
        val currentProject = loadCurrentProject(currentProjectId) ?: return false
        val tracks = selectedPlayableTracks()
        if (tracks.isEmpty()) return false
        val timeline =
            timelineProjection(timelineTracksForPlayhead(), waveformStatesByTrackId())
        val startPositionMs =
            timelinePlayheadClampedPositionMs(playheadPositionMs.value, timeline.timelineDurationMs)
        if (
            !playbackStartAllowedAtPlayhead(
                startPositionMs = startPositionMs,
                timelineBaseDurationMs = timeline.baseTimelineDurationMs,
                tracks = tracks,
            )
        ) {
            return false
        }
        val playbackSpec =
            currentProject.toMultiPlaybackSpec(tracks)?.copy(
                startPositionMs = startPositionMs,
                sessionTimelineEndMs = sessionTimelineEndMsForPlayback(tracks),
            ) ?: return false
        if (
            !playbackSession.restartEngineFromPlayhead(
                playbackSpec,
                tracks.map { it.id },
                selectedTrackIds(),
            )
        ) {
            return false
        }
        playheadTransport.onPlaybackStarted(fromPositionMs = startPositionMs)
        return true
    }

    fun abortPlaybackSeekDragToPaused() {
        if (playheadTransport.isPlaybackSeekDragActive()) {
            playheadTransport.endPlaybackSeekDragAndConsumeResume()
        }
        if (playheadTransport.phase.value != TransportPlaybackPhase.Playing) return
        playheadTransport.enterPaused()
        transportController.pausePlayback()
    }
}
