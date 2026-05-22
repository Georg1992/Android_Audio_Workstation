package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.toMultiPlaybackSpec
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity

/**
 * Overdub playback around an active recording take: selected lanes only, same [startPositionMs] as
 * the recording offset, never includes the recording row. Playhead timing stays in
 * [PlayheadTransportController] Recording phase polls the same native transport clock as playback.
 */
class PlayAndRecordTransport(
    private val audioController: AudioController,
    private val playbackSession: PlaybackSessionController,
) {
    /**
     * @return true when overdub playback is not needed or native playback accepted the spec.
     * @return false when lanes were required but [AudioController.startPlayback] rejected them.
     */
    fun startFromPlayhead(
        project: ProjectEntity,
        selectedPlayableTracks: List<TrackEntity>,
        recordingTrackId: String,
        startPositionMs: Long,
        sessionTimelineEndMs: Long,
    ): Boolean {
        val overdubLanes =
            selectedPlayableTracks
                .filter { it.id != recordingTrackId }
                .filter { it.wavFilePath.isNotBlank() }
        if (overdubLanes.isEmpty()) {
            return true
        }
        val spec =
            project.toMultiPlaybackSpec(overdubLanes)?.copy(
                startPositionMs = startPositionMs,
                sessionTimelineEndMs = sessionTimelineEndMs,
            ) ?: return false
        if (!audioController.startPlayback(spec)) {
            return false
        }
        playbackSession.markPlayingAndStartCompletionMonitor(spec.lanes.map { it.trackId })
        return true
    }

    fun stop() {
        playbackSession.cancelCompletionMonitorForTransportStop()
        playbackSession.stopEngineIfMarkedPlaying()
        playbackSession.clearPlayingTransportState()
    }
}
