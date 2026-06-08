package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.toMultiPlaybackSpec
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.core.track.activeMixScopeOverdubPlaybackTracks
import com.georgv.audioworkstation.core.track.activeMixScopePlayableTracks
import com.georgv.audioworkstation.core.track.playbackMustStopAtScopeEnd
import com.georgv.audioworkstation.core.track.playheadMsAfterScopeStop
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.playbackStartAllowedAtPlayhead
import com.georgv.audioworkstation.ui.components.sessionTimelineEndMsForPlayback
import com.georgv.audioworkstation.ui.components.timelinePlayheadClampedPositionMs
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Rebuilds or stops native playback when [selectedTrackIds] changes during an active transport session.
 * Selection is the single source of truth for playback scope (no mute/hot-join path).
 */
internal class ScopePlaybackCoordinator(
    private val audioController: AudioController,
    private val dispatchers: AppDispatchers,
    private val playheadPositionMs: MutableStateFlow<Long>,
    private val playheadTransport: PlayheadTransportController,
    private val playbackSession: PlaybackSessionController,
    private val transportController: ProjectTransportController,
    private val recordingSession: RecordingSessionController,
    private val playAndRecordTransport: PlayAndRecordTransport,
    private val projectId: () -> String?,
    private val visibleTracks: () -> List<TrackEntity>,
    private val loadCurrentProject: suspend (String) -> ProjectEntity?,
    private val timelineVisibleDurationMs: () -> Long,
    private val timelineBaseDurationMs: () -> Long,
) {
    suspend fun onSelectionChangedDuringTransport(selectedTrackIds: Set<String>) {
        if (!playbackSession.hasActivePlaybackSession()) return

        val recordingTrackId = recordingSession.recordingTrackId.value
        val tracks = visibleTracks()
        val scopePlayableTracks = activeMixScopePlayableTracks(tracks, selectedTrackIds)
        val transportMs = readTransportMs()

        if (selectedTrackIds.isEmpty()) {
            stopForScopeChange(
                clampPlayheadMs = 0L,
                recordingTrackId = recordingTrackId,
            )
            return
        }

        if (scopePlayableTracks.isEmpty()) {
            if (recordingTrackId != null) {
                rebuildOverdubBacking(selectedTrackIds, recordingTrackId, transportMs)
            } else {
                stopForScopeChange(clampPlayheadMs = 0L, recordingTrackId = null)
            }
            return
        }

        if (playbackMustStopAtScopeEnd(transportMs, scopePlayableTracks)) {
            stopForScopeChange(
                clampPlayheadMs =
                    playheadMsAfterScopeStop(
                        transportMs = transportMs,
                        scopePlayableTracks = scopePlayableTracks,
                        selectionEmpty = false,
                    ),
                recordingTrackId = recordingTrackId,
            )
            return
        }

        if (recordingTrackId != null) {
            rebuildOverdubBacking(selectedTrackIds, recordingTrackId, transportMs)
            return
        }

        rebuildPlaybackScope(selectedTrackIds, scopePlayableTracks, transportMs)
    }

    suspend fun rebuildPlaybackAtCurrentTransport(selectedTrackIds: Set<String>): Boolean {
        if (!playbackSession.hasActivePlaybackSession()) return false
        val tracks = activeMixScopePlayableTracks(visibleTracks(), selectedTrackIds)
        if (tracks.isEmpty()) return false
        val transportMs =
            timelinePlayheadClampedPositionMs(playheadPositionMs.value, timelineVisibleDurationMs())
        if (
            !playbackStartAllowedAtPlayhead(
                startPositionMs = transportMs,
                timelineBaseDurationMs = timelineBaseDurationMs(),
                tracks = tracks,
            )
        ) {
            return false
        }
        return rebuildPlaybackScope(selectedTrackIds, tracks, transportMs)
    }

    private suspend fun rebuildPlaybackScope(
        selectedTrackIds: Set<String>,
        scopePlayableTracks: List<TrackEntity>,
        transportMs: Long,
    ): Boolean {
        val currentProjectId = projectId() ?: return false
        val currentProject = loadCurrentProject(currentProjectId) ?: return false
        val startPositionMs =
            timelinePlayheadClampedPositionMs(transportMs, timelineVisibleDurationMs())
        if (
            !playbackStartAllowedAtPlayhead(
                startPositionMs = startPositionMs,
                timelineBaseDurationMs = timelineBaseDurationMs(),
                tracks = scopePlayableTracks,
            )
        ) {
            return false
        }
        val playbackSpec =
            currentProject.toMultiPlaybackSpec(scopePlayableTracks)?.copy(
                startPositionMs = startPositionMs,
                sessionTimelineEndMs = sessionTimelineEndMsForPlayback(scopePlayableTracks),
            ) ?: return false
        if (
            !playbackSession.restartEngineFromPlayhead(
                spec = playbackSpec,
                trackIdsInLaneOrder = scopePlayableTracks.map { it.id },
                selectedTrackIds = selectedTrackIds,
            )
        ) {
            return false
        }
        playheadPositionMs.value = startPositionMs
        if (playheadTransport.phase.value == TransportPlaybackPhase.Playing) {
            playheadTransport.onPlaybackStarted(fromPositionMs = startPositionMs)
        }
        return true
    }

    private suspend fun rebuildOverdubBacking(
        selectedTrackIds: Set<String>,
        recordingTrackId: String,
        transportMs: Long,
    ) {
        val tracks = visibleTracks()
        val overdubTracks =
            activeMixScopeOverdubPlaybackTracks(
                tracks = tracks,
                selectedTrackIds = selectedTrackIds,
                recordingTrackId = recordingTrackId,
            )
        playbackSession.cancelCompletionMonitorForTransportStop()
        playbackSession.pauseEnginePreservingSession()
        if (overdubTracks.isEmpty()) {
            playbackSession.clearPlayingTransportState()
            return
        }
        val currentProjectId = projectId() ?: return
        val currentProject = loadCurrentProject(currentProjectId) ?: return
        val sessionEndMs = sessionTimelineEndMsForPlayback(overdubTracks)
        if (playbackMustStopAtScopeEnd(transportMs, overdubTracks)) {
            playbackSession.clearPlayingTransportState()
            return
        }
        val started =
            playAndRecordTransport.startFromPlayhead(
                project = currentProject,
                selectedPlayableTracks = overdubTracks,
                recordingTrackId = recordingTrackId,
                startPositionMs = transportMs,
                sessionTimelineEndMs = sessionEndMs,
            )
        if (!started) {
            playbackSession.clearPlayingTransportState()
        }
    }

    private suspend fun stopForScopeChange(
        clampPlayheadMs: Long,
        recordingTrackId: String?,
    ) {
        playbackSession.cancelCompletionMonitorForTransportStop()
        withAudioIo(dispatchers, "AudioController.stopPlayback scopeChange") {
            playbackSession.stopEngineIfMarkedPlaying()
        }
        playbackSession.clearPlayingTransportState()
        playheadPositionMs.value = clampPlayheadMs.coerceAtLeast(0L)
        when (playheadTransport.phase.value) {
            TransportPlaybackPhase.Recording -> Unit
            TransportPlaybackPhase.Playing,
            TransportPlaybackPhase.Paused,
            -> playheadTransport.enterPaused()
            TransportPlaybackPhase.Idle -> Unit
        }
        if (recordingTrackId == null) {
            transportController.pausePlayback()
        }
    }

    private fun readTransportMs(): Long =
        audioController.transportPositionMs().coerceAtLeast(0L)
}
