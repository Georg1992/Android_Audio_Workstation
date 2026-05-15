package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController

/**
 * Thin façade for coordinated transport teardown and playback reset when the bound project changes.
 *
 * Recording transport/session markers live in [RecordingSessionController]; this type sequences engine
 * + session calls and invokes the ViewModel-supplied finalize callback after a successful recorder stop.
 *
 * **Stop ordering (behavior preserved from Phase 0):**
 * 1. Cancel playback completion monitoring ([PlaybackSessionController.cancelCompletionMonitorForTransportStop]).
 * 2. Set recording startup flag to false ([RecordingSessionController.clearStartupFlagForTransportStop]).
 * 3. If a recording row was active (non-null id) and [AudioController.stopRecording] succeeds, invoke finalize callback.
 * 4. If playback was marked active, [AudioController.stopPlayback].
 * 5. Clear recording markers ([RecordingSessionController.clearRecordingTransportMarkers]).
 * 6. Clear playing markers ([PlaybackSessionController.clearPlayingTransportState]).
 */
class ProjectTransportController(
    private val audioController: AudioController,
    private val playbackSession: PlaybackSessionController,
    private val recordingSession: RecordingSessionController,
    private val finalizeRecordingTrackAfterSuccessfulEngineStop: (String) -> Unit,
) {

    /** Full user / lifecycle transport stop — same sequencing as legacy [ProjectViewModel.performTransportStopSequence]. */
    fun stopAll() {
        playbackSession.cancelCompletionMonitorForTransportStop()

        recordingSession.clearStartupFlagForTransportStop()

        val activeRecordingTrackId = recordingSession.activeRecordingTrackIdForTransport()
        if (activeRecordingTrackId != null && audioController.stopRecording()) {
            finalizeRecordingTrackAfterSuccessfulEngineStop(activeRecordingTrackId)
        }

        playbackSession.stopEngineIfMarkedPlaying()

        recordingSession.clearRecordingTransportMarkers()
        playbackSession.clearPlayingTransportState()
    }

    /** Playback markers only — used when navigating to another project ([ProjectViewModel.bind]). */
    fun resetPlaybackForProjectChange() {
        playbackSession.resetWhenProjectChanges()
    }
}
