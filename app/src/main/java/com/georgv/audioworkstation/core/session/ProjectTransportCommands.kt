package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.MeterPort
import com.georgv.audioworkstation.core.audio.PlaybackPort
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.core.audio.PlaybackTransportSync
import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import com.georgv.audioworkstation.core.audio.toLiveEnginePlaybackSpec
import com.georgv.audioworkstation.core.audio.TransportTimelinePolicy
import com.georgv.audioworkstation.core.coroutines.withIo
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.core.track.activeMixScopePlayableTracks
import com.georgv.audioworkstation.core.audio.playbackStartAllowedAtPlayhead
import com.georgv.audioworkstation.core.timeline.timelinePlayheadClampedPositionMs
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Play / stop / record transport actions for the project screen.
 * Keeps [ProjectViewModel] focused on UI state wiring and track CRUD.
 */
internal class ProjectTransportCommands(
    private val playback: PlaybackPort,
    private val meter: MeterPort,
    private val dispatchers: AppDispatchers,
    private val sessionTransportGate: SessionTransportCapabilityGate,
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
        val overdubPlaybackStartMs =
            TransportTimelinePolicy.playbackStartPositionMsForTracks(
                scrubbedPlayheadMs = timelineStartOffsetMs,
                timelineVisibleDurationMs = timelineVisibleDurationMs(),
                tracks = overdubPlaybackTracks,
            ).value
        val hasOverdubBacking = overdubPlaybackTracks.isNotEmpty()
        val recordingTimelineStartOffsetMs =
            TransportTimelinePolicy.recordingClipTimelineStartMs(
                playheadMs = timelineStartOffsetMs,
                overdubPlaybackStartMs = overdubPlaybackStartMs,
                hasOverdubBacking = hasOverdubBacking,
            )

        if (hasOverdubBacking) {
            playheadPositionMs.value = overdubPlaybackStartMs
        }

        recordingSession.armRecordingStartup()
        launchRecordPressed(
            projectId = projectId,
            projectName = projectName,
            timelineStartOffsetMs = timelineStartOffsetMs,
            recordTargetTrack = recordTargetTrack,
            overdubPlaybackTracks = overdubPlaybackTracks,
            overdubPlaybackStartMs = overdubPlaybackStartMs,
        )
    }

    private fun launchRecordPressed(
        projectId: String,
        projectName: String,
        timelineStartOffsetMs: Long,
        recordTargetTrack: TrackEntity?,
        overdubPlaybackTracks: List<TrackEntity>,
        overdubPlaybackStartMs: Long,
    ) {
        recordingSession.launchRecordPressed(
            projectId = projectId,
            projectName = projectName,
            timelineStartOffsetMs = timelineStartOffsetMs,
            ensureProject = ensureProject,
            visibleTrackCount = visibleTrackCount,
            persistRecordingRow = persistRecordingRow,
            recordTargetTrack = recordTargetTrack,
            overdubPlaybackTracks = overdubPlaybackTracks,
            overdubPlaybackStartMs = overdubPlaybackStartMs,
            onPendingTrackAllocated = { pendingTrack ->
                overdubPlaybackTracks.takeIf { it.isNotEmpty() }?.let { lanes ->
                    playbackSession.markPlayingAndStartCompletionMonitor(
                        lanes.map { it.id },
                    )
                }
                true
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
        val audibleStart =
            TransportTimelinePolicy.playbackStartPositionMsForTracks(
                scrubbedPlayheadMs = playheadPositionMs.value,
                timelineVisibleDurationMs = timelineVisibleDurationMs(),
                tracks = selectedPlayableTracks,
            )
        if (
            !playbackStartAllowedAtPlayhead(
                startPositionMs = audibleStart.value,
                timelineBaseDurationMs = timelineBaseDurationMs(),
                tracks = selectedPlayableTracks,
            )
        ) {
            return
        }

        var armedTrackIds: List<String> = emptyList()
        val started =
            withAudioIo(dispatchers, "PlaybackPort.startPlayback") {
                withIo(dispatchers, "prepare session transport capability") {
                    sessionTransportGate.prepareForLiveSession(currentProject.sampleRate)
                }
                PlaybackTransportSync.requirePreparedCapability(sessionTransportGate)
                val outputLatencyMs = PlaybackTransportSync.effectiveOutputLatencyMsForUiSync(meter)
                val playbackSpec =
                    currentProject.toLiveEnginePlaybackSpec(
                        tracks = selectedPlayableTracks,
                        startPositionMs = PlaybackTransportSync.mixTransportMs(audibleStart, outputLatencyMs),
                    ) ?: return@withAudioIo false
                armedTrackIds = playbackSpec.lanes.map { it.trackId }
                playback.startPlayback(playbackSpec)
            }
        if (!started) {
            playheadTransport.abortPlaybackStart()
            emitMessage(R.string.error_playback_failed_to_start)
            return
        }
        playheadPositionMs.value = audibleStart.value
        playheadTransport.onPlaybackStarted(fromPositionMs = audibleStart.value)
        playbackSession.markPlayingAndStartCompletionMonitor(armedTrackIds)
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

    private fun selectedPlayableTracks(): List<TrackEntity> =
        activeMixScopePlayableTracks(visibleTracks(), selectedTrackIds())

    private fun selectedPlayableTracksForOverdub(tracks: List<TrackEntity>): List<TrackEntity> =
        activeMixScopePlayableTracks(tracks, selectedTrackIds())

    private suspend fun abortCombinedRecordTransport() {
        playAndRecordTransport.stop()
        playheadTransport.abortRecordingStart()
    }
}
