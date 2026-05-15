package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.data.db.entities.TrackEntity

/**
 * Thin façade for coordinated transport teardown and playback reset when the bound project changes.
 *
 * Recording row state remains in [ProjectViewModel]; this type only sequences engine + session calls
 * and drives the ViewModel-supplied clears/finalize callback.
 *
 * **Stop ordering (behavior preserved from Phase 0):**
 * 1. Cancel playback completion monitoring ([PlaybackSessionController.cancelCompletionMonitorForTransportStop]).
 * 2. Set recording startup flag to false.
 * 3. If a recording row was active (non-null id) and [AudioController.stopRecording] succeeds, invoke finalize callback.
 * 4. If playback was marked active, [AudioController.stopPlayback].
 * 5. Clear [recordingTrackId] (via callback).
 * 6. Clear [optimisticRecordingTrack] (via callback).
 * 7. Clear playing markers ([PlaybackSessionController.clearPlayingTransportState]).
 */
class ProjectTransportController(
    private val audioController: AudioController,
    private val playbackSession: PlaybackSessionController,
    private val getRecordingTrackId: () -> String?,
    private val setRecordingTrackId: (String?) -> Unit,
    private val setOptimisticRecordingTrack: (TrackEntity?) -> Unit,
    private val setRecordingStartup: (Boolean) -> Unit,
    private val finalizeRecordingTrackAfterSuccessfulEngineStop: (String) -> Unit,
) {

    /** Full user / lifecycle transport stop — same sequencing as legacy [ProjectViewModel.performTransportStopSequence]. */
    fun stopAll() {
        playbackSession.cancelCompletionMonitorForTransportStop()

        setRecordingStartup(false)

        val activeRecordingTrackId = getRecordingTrackId()
        if (activeRecordingTrackId != null && audioController.stopRecording()) {
            finalizeRecordingTrackAfterSuccessfulEngineStop(activeRecordingTrackId)
        }

        playbackSession.stopEngineIfMarkedPlaying()

        setRecordingTrackId(null)
        setOptimisticRecordingTrack(null)
        playbackSession.clearPlayingTransportState()
    }

    /** Playback markers only — used when navigating to another project ([ProjectViewModel.bind]). */
    fun resetPlaybackForProjectChange() {
        playbackSession.resetWhenProjectChanges()
    }
}
