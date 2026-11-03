package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.core.audio.MeterPort
import com.georgv.audioworkstation.core.audio.AudibleMs
import com.georgv.audioworkstation.core.audio.MixTransportMs
import com.georgv.audioworkstation.core.audio.PlaybackTransportSync
import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import com.georgv.audioworkstation.core.audio.sessionPlaybackSchedulingEndMsForPlayback
import com.georgv.audioworkstation.core.audio.toLiveEnginePlaybackSpec
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.core.track.activeMixScopeOverdubPlaybackTracks
import com.georgv.audioworkstation.core.track.activeMixScopePlayableTracks
import com.georgv.audioworkstation.core.track.streamsSharedTake
import com.georgv.audioworkstation.core.util.logWarning
import kotlinx.coroutines.CancellationException
import com.georgv.audioworkstation.core.track.playbackMustStopAtScopeEnd
import com.georgv.audioworkstation.core.track.playheadMsAfterScopeStop
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.core.audio.playbackStartAllowedAtPlayhead
import com.georgv.audioworkstation.core.timeline.timelinePlayheadClampedPositionMs
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Rebuilds or stops native playback when [selectedTrackIds] changes during an active transport session.
 * Selection is the single source of truth for playback scope (full spec rebuild).
 *
 * Coordinate contract:
 * - [MixTransportMs] — raw native transport; passed to engine arm/rebuild.
 * - [AudibleMs] — UI playhead / scrubber; never passed to native without conversion.
 */
internal class ScopePlaybackCoordinator(
    private val meter: MeterPort,
    private val dispatchers: AppDispatchers,
    private val sessionTransportGate: SessionTransportCapabilityGate,
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
        val mixTransportMs = readMixTransportMs()

        if (selectedTrackIds.isEmpty()) {
            stopForScopeChange(
                clampMixTransportMs = MixTransportMs(0L),
                recordingTrackId = recordingTrackId,
            )
            return
        }

        if (scopePlayableTracks.isEmpty()) {
            if (recordingTrackId != null) {
                rebuildOverdubBacking(selectedTrackIds, recordingTrackId, mixTransportMs)
            } else {
                stopForScopeChange(clampMixTransportMs = MixTransportMs(0L), recordingTrackId = null)
            }
            return
        }

        if (playbackMustStopAtScopeEnd(mixTransportMs, scopePlayableTracks)) {
            stopForScopeChange(
                clampMixTransportMs =
                    playheadMsAfterScopeStop(
                        mixTransportMs = mixTransportMs,
                        scopePlayableTracks = scopePlayableTracks,
                        selectionEmpty = false,
                    ),
                recordingTrackId = recordingTrackId,
            )
            return
        }

        if (recordingTrackId != null) {
            rebuildOverdubBacking(selectedTrackIds, recordingTrackId, mixTransportMs)
            return
        }

        rebuildPlaybackScope(selectedTrackIds, scopePlayableTracks, mixTransportMs)
    }

    suspend fun rebuildPlaybackAtCurrentTransport(selectedTrackIds: Set<String>): Boolean {
        if (!playbackSession.hasActivePlaybackSession()) return false
        val tracks = activeMixScopePlayableTracks(visibleTracks(), selectedTrackIds)
        if (tracks.isEmpty()) return false
        val audibleMs =
            AudibleMs(
                timelinePlayheadClampedPositionMs(playheadPositionMs.value, timelineVisibleDurationMs()),
            )
        if (
            !playbackStartAllowedAtPlayhead(
                startPositionMs = audibleMs.value,
                timelineBaseDurationMs = timelineBaseDurationMs(),
                tracks = tracks,
            )
        ) {
            return false
        }
        val mixTransportMs = PlaybackTransportSync.mixTransportMs(meter, audibleMs)
        return rebuildPlaybackScope(selectedTrackIds, tracks, mixTransportMs, audibleMs)
    }

    private suspend fun rebuildPlaybackScope(
        selectedTrackIds: Set<String>,
        scopePlayableTracks: List<TrackEntity>,
        mixTransportMs: MixTransportMs,
        audiblePlayheadMs: AudibleMs =
            PlaybackTransportSync.audiblePlayheadMs(meter, mixTransportMs),
    ): Boolean {
        val currentProjectId = projectId() ?: return false
        val currentProject = loadCurrentProject(currentProjectId) ?: return false
        val clampedAudible =
            AudibleMs(
                timelinePlayheadClampedPositionMs(audiblePlayheadMs.value, timelineVisibleDurationMs()),
            )
        if (
            !playbackStartAllowedAtPlayhead(
                startPositionMs = clampedAudible.value,
                timelineBaseDurationMs = timelineBaseDurationMs(),
                tracks = scopePlayableTracks,
            )
        ) {
            return false
        }
        PlaybackTransportSync.requirePreparedCapability(sessionTransportGate)
        val localTracks = scopePlayableTracks.filter { it.wavFilePath.isNotBlank() }
        val streamedTracks = scopePlayableTracks.filter { it.streamsSharedTake() }
        val playbackSpec = if (localTracks.isEmpty()) {
            null
        } else {
            currentProject.toLiveEnginePlaybackSpec(tracks = localTracks, startPositionMs = mixTransportMs)
                ?.copy(
                    sessionTimelineEndMs = sessionPlaybackSchedulingEndMsForPlayback(scopePlayableTracks),
                )
        }
        if (localTracks.isNotEmpty() && playbackSpec == null) return false
        if (playbackSpec != null &&
            !playbackSession.restartEngineFromPlayhead(
                spec = playbackSpec,
                trackIdsInLaneOrder = localTracks.map { it.id },
                selectedTrackIds = selectedTrackIds,
            )
        ) {
            return false
        }
        if (!armStreams(currentProject.id, localTracks, streamedTracks, clampedAudible.value)) return false
        playheadPositionMs.value = clampedAudible.value
        if (playheadTransport.phase.value == TransportPlaybackPhase.Playing) {
            playheadTransport.onPlaybackStarted(fromPositionMs = clampedAudible.value)
        }
        return true
    }

    private suspend fun rebuildOverdubBacking(
        selectedTrackIds: Set<String>,
        recordingTrackId: String,
        mixTransportMs: MixTransportMs,
    ) {
        val tracks = visibleTracks()
        val overdubTracks =
            activeMixScopeOverdubPlaybackTracks(
                tracks = tracks,
                selectedTrackIds = selectedTrackIds,
                recordingTrackId = recordingTrackId,
            )
        playbackSession.cancelCompletionMonitorForTransportStop()
        if (overdubTracks.isEmpty()) {
            playbackSession.clearPlayingTransportState()
            return
        }
        val currentProjectId = projectId() ?: return
        val currentProject = loadCurrentProject(currentProjectId) ?: return
        if (playbackMustStopAtScopeEnd(mixTransportMs, overdubTracks)) {
            playbackSession.clearPlayingTransportState()
            return
        }
        val started =
            playAndRecordTransport.rebuildOverdubAtCurrentTransport(
                project = currentProject,
                selectedPlayableTracks = overdubTracks,
                recordingTrackId = recordingTrackId,
                mixTransportMs = mixTransportMs,
                timelineVisibleDurationMs = timelineVisibleDurationMs(),
            )
        if (!started) {
            playbackSession.clearPlayingTransportState()
        }
    }

    private suspend fun stopForScopeChange(
        clampMixTransportMs: MixTransportMs,
        recordingTrackId: String?,
    ) {
        playbackSession.cancelCompletionMonitorForTransportStop()
        withAudioIo(dispatchers, "PlaybackPort.stopPlayback scopeChange") {
            playbackSession.stopEngineIfMarkedPlaying()
        }
        ProjectTrackStreams.player.release()
        playbackSession.clearPlayingTransportState()
        playheadPositionMs.value =
            PlaybackTransportSync.audiblePlayheadMs(meter, clampMixTransportMs).value
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

    private suspend fun armStreams(
        projectId: String,
        localTracks: List<TrackEntity>,
        streamedTracks: List<TrackEntity>,
        positionMs: Long,
    ): Boolean {
        val player = ProjectTrackStreams.player
        try {
            if (streamedTracks.isEmpty()) {
                player.release()
            } else if (player.sameTracks(streamedTracks)) {
                player.seekTo(positionMs)
                player.useStreamClock(localTracks.isEmpty())
            } else {
                player.play(projectId, streamedTracks, positionMs, localTracks.isEmpty())
            }
        } catch (cancel: CancellationException) {
            player.release()
            throw cancel
        } catch (error: Exception) {
            player.release()
            logWarning(Tag, "shared track seek failed", error)
            return false
        }
        return true
    }

    private fun readMixTransportMs(): MixTransportMs {
        val raw = if (ProjectTrackStreams.player.ownsClock()) {
            ProjectTrackStreams.player.positionMs()
        } else {
            meter.transportPositionMs()
        }
        return PlaybackTransportSync.mixTransportMsFromRaw(raw.coerceAtLeast(0L))
    }

    private companion object {
        const val Tag = "ScopePlayback"
    }
}
