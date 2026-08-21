package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.core.audio.PlaybackPort
import com.georgv.audioworkstation.core.audio.MixTransportMs
import com.georgv.audioworkstation.core.audio.toLiveEnginePlaybackSpec
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity

/**
 * Overdub playback around an active recording take: selected lanes only, never includes the
 * recording row. Playhead timing stays in [PlayheadTransportController]; Recording phase polls
 * the same native transport clock as playback.
 *
 * Initial overdub arm uses [CapturePort.startOverdubRecordingSession] with immediate playback.
 */
class PlayAndRecordTransport(
    private val playback: PlaybackPort,
    private val playbackSession: PlaybackSessionController,
    private val dispatchers: AppDispatchers,
) {
    /**
     * Rebuild overdub backing during an active recording session at the current mix transport
     * (e.g. scope change while recording) without resetting the overdub capture anchor.
     */
    suspend fun rebuildOverdubAtCurrentTransport(
        project: ProjectEntity,
        selectedPlayableTracks: List<TrackEntity>,
        recordingTrackId: String,
        mixTransportMs: MixTransportMs,
        @Suppress("UNUSED_PARAMETER") timelineVisibleDurationMs: Long,
    ): Boolean {
        val overdubLanes =
            selectedPlayableTracks
                .filter { it.id != recordingTrackId }
                .filter { it.wavFilePath.isNotBlank() }
        if (overdubLanes.isEmpty()) {
            return true
        }
        val spec =
            project.toLiveEnginePlaybackSpec(
                tracks = overdubLanes,
                startPositionMs = mixTransportMs,
            ) ?: return false
        val started =
            withAudioIo(dispatchers, "PlaybackPort.rearmOverdubPlaybackDuringRecording") {
                playback.rearmOverdubPlaybackDuringRecording(spec)
            }
        if (!started) {
            return false
        }
        playbackSession.markPlayingAndStartCompletionMonitor(spec.lanes.map { it.trackId })
        return true
    }

    suspend fun stop() {
        playbackSession.cancelCompletionMonitorForTransportStop()
        playbackSession.stopEngineIfMarkedPlaying()
        playbackSession.clearPlayingTransportState()
    }
}
