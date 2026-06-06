package com.georgv.audioworkstation.ui.screens.projects

import androidx.annotation.StringRes
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.toMultiPlaybackSpec
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.playbackStartAllowedAtPlayhead
import com.georgv.audioworkstation.ui.components.sessionTimelineEndMsForPlayback
import com.georgv.audioworkstation.ui.components.timelinePlayheadClampedPositionMs
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Play / stop / record transport actions for the project screen.
 * Keeps [ProjectViewModel] focused on UI state wiring and track CRUD.
 */
internal class ProjectTransportCommands(
    private val audioController: AudioController,
    private val dispatchers: AppDispatchers,
    private val playheadPositionMs: MutableStateFlow<Long>,
    private val playheadTransport: PlayheadTransportController,
    private val playbackSession: PlaybackSessionController,
    private val recordingSession: RecordingSessionController,
    private val transportController: ProjectTransportController,
    private val playheadSeek: ProjectPlayheadSeekCoordinator,
    private val playAndRecordTransport: PlayAndRecordTransport,
    private val projectId: () -> String?,
    private val selectedTrackIds: () -> Set<String>,
    private val visibleTracks: () -> List<TrackEntity>,
    private val visibleTrackCount: () -> Int,
    private val recordTargetTrackId: () -> String?,
    private val timelineVisibleDurationMs: () -> Long,
    private val timelineBaseDurationMs: () -> Long,
    private val loadCurrentProject: suspend (String) -> ProjectEntity?,
    private val ensureProject: suspend (String, String) -> ProjectEntity?,
    private val persistRecordingRow: suspend (TrackEntity) -> Unit,
    private val emitMessage: (Int) -> Unit,
    private val storagePrecheck: suspend (ProjectEntity) -> Boolean,
    private val onRecordingStorageMonitorStart: (String) -> Unit,
    private val onRecordingStorageMonitorStop: () -> Unit,
    private val isImportInProgress: () -> Boolean,
) {
    fun onRecordPressed(projectId: String, projectName: String = "New Project") {
        if (recordingSession.hasActiveRecordingTake()) {
            emitMessage(R.string.error_stop_recording_to_record)
            return
        }
        if (recordingSession.isStartupInFlight()) {
            return
        }
        if (isImportInProgress()) {
            emitMessage(R.string.error_wait_for_import_before_recording)
            return
        }

        val tracks = visibleTracks()
        val timelineStartOffsetMs =
            timelinePlayheadClampedPositionMs(playheadPositionMs.value, timelineVisibleDurationMs())

        val overdubPlaybackTracks = selectedPlayableTracksForOverdub(tracks)
        val recordTargetTrack =
            recordTargetTrackId()?.let { targetId -> tracks.find { it.id == targetId } }

        recordingSession.armRecordingStartup()

        recordingSession.launchRecordPressed(
            projectId = projectId,
            projectName = projectName,
            timelineStartOffsetMs = timelineStartOffsetMs,
            ensureProject = ensureProject,
            visibleTrackCount = visibleTrackCount,
            persistRecordingRow = persistRecordingRow,
            recordTargetTrack = recordTargetTrack,
            onPendingTrackAllocated = { pendingTrack ->
                val overdubStarted =
                    startOverdubPlaybackForPendingTake(
                        projectId = projectId,
                        pendingTrack = pendingTrack,
                        timelineStartOffsetMs = timelineStartOffsetMs,
                        sessionTimelineEndMs = sessionTimelineEndMsForPlayback(overdubPlaybackTracks),
                        selectedPlayableTracks = overdubPlaybackTracks,
                    )
                if (!overdubStarted) {
                    abortCombinedRecordTransport()
                    onRecordingStorageMonitorStop()
                    emitMessage(R.string.error_playback_failed_to_start)
                }
                overdubStarted
            },
            notifyEngineStartFailed = {
                abortCombinedRecordTransport()
                onRecordingStorageMonitorStop()
                emitMessage(R.string.error_recording_failed_to_start)
            },
            notifyPersistFailed = {
                abortCombinedRecordTransport()
                onRecordingStorageMonitorStop()
                emitMessage(R.string.error_create_recording_track_failed)
            },
            storagePrecheck = storagePrecheck,
            notifyStorageStartBlocked = {
                emitMessage(R.string.error_recording_storage_insufficient_start)
            },
            onRecordingTransportReady = { offsetMs ->
                playheadTransport.onRecordingStarted(fromPositionMs = offsetMs)
                onRecordingStorageMonitorStart(projectId)
            },
        )
    }

    suspend fun performPlayPressed() {
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) {
            return
        }
        if (playheadTransport.phase.value == TransportPlaybackPhase.Recording) {
            return
        }
        if (playheadTransport.phase.value == TransportPlaybackPhase.Playing) {
            emitMessage(R.string.error_stop_playback_first)
            return
        }

        val selectedPlayableTracks = selectedPlayableTracks()
        if (selectedPlayableTracks.isEmpty()) {
            if (selectedTrackIds().isNotEmpty()) {
                emitMessage(R.string.error_no_audio_for_selected_tracks)
            }
            return
        }
        val currentProjectId = projectId() ?: return
        val currentProject = loadCurrentProject(currentProjectId) ?: return
        val startPositionMs =
            timelinePlayheadClampedPositionMs(playheadPositionMs.value, timelineVisibleDurationMs())
        if (
            !playbackStartAllowedAtPlayhead(
                startPositionMs = startPositionMs,
                timelineBaseDurationMs = timelineBaseDurationMs(),
                tracks = selectedPlayableTracks,
            )
        ) {
            return
        }

        val playbackSpec =
            currentProject.toMultiPlaybackSpec(selectedPlayableTracks)?.copy(
                startPositionMs = startPositionMs,
                sessionTimelineEndMs = sessionTimelineEndMsForPlayback(selectedPlayableTracks),
            )
        if (playbackSpec == null) {
            emitMessage(playbackStartRejectedMessage(selectedPlayableTracks.size))
            return
        }

        val started =
            withAudioIo(dispatchers, "AudioController.startPlayback") {
                audioController.startPlayback(playbackSpec)
            }
        if (!started) {
            playheadTransport.abortPlaybackStart()
            emitMessage(R.string.error_playback_failed_to_start)
            return
        }
        playheadTransport.onPlaybackStarted(fromPositionMs = startPositionMs)
        playbackSession.markPlayingAndStartCompletionMonitor(
            playbackSpec.lanes.map { it.trackId },
        )
    }

    suspend fun performStopPressed() {
        if (
            recordingSession.hasActiveRecordingTake() ||
            recordingSession.isStartupInFlight()
        ) {
            onRecordingStorageMonitorStop()
            transportController.stopAll()
            playheadTransport.stopAndResetToZero()
            return
        }

        when (playheadTransport.phase.value) {
            TransportPlaybackPhase.Playing -> {
                playheadSeek.abortPlaybackSeekDragToPaused()
            }
            TransportPlaybackPhase.Paused -> {
                transportController.pausePlayback()
                playheadTransport.stopAndResetToZero()
            }
            TransportPlaybackPhase.Idle -> Unit
            TransportPlaybackPhase.Recording -> Unit
        }
    }

    private fun selectedPlayableTracks(): List<TrackEntity> {
        val selected = selectedTrackIds()
        if (selected.isEmpty()) return emptyList()
        return visibleTracks()
            .filter { it.id in selected }
            .filter { it.wavFilePath.isNotBlank() }
    }

    private fun selectedPlayableTracksForOverdub(tracks: List<TrackEntity>): List<TrackEntity> {
        val selected = selectedTrackIds()
        if (selected.isEmpty()) return emptyList()
        return tracks
            .filter { it.id in selected }
            .filter { it.wavFilePath.isNotBlank() }
    }

    private suspend fun startOverdubPlaybackForPendingTake(
        projectId: String,
        pendingTrack: TrackEntity,
        timelineStartOffsetMs: Long,
        sessionTimelineEndMs: Long,
        selectedPlayableTracks: List<TrackEntity>,
    ): Boolean {
        if (selectedPlayableTracks.isEmpty()) {
            return true
        }
        val project = loadCurrentProject(projectId) ?: return false
        return playAndRecordTransport.startFromPlayhead(
            project = project,
            selectedPlayableTracks = selectedPlayableTracks,
            recordingTrackId = pendingTrack.id,
            startPositionMs = timelineStartOffsetMs,
            sessionTimelineEndMs = sessionTimelineEndMs,
        )
    }

    private suspend fun abortCombinedRecordTransport() {
        playAndRecordTransport.stop()
        playheadTransport.abortRecordingStart()
    }

    @StringRes
    private fun playbackStartRejectedMessage(playableTrackCount: Int): Int =
        if (playableTrackCount == 0) {
            R.string.error_no_audio_for_selected_tracks
        } else {
            R.string.error_playback_failed_to_start
        }
}
