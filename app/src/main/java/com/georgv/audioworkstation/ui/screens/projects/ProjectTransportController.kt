package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo

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
    private val dispatchers: AppDispatchers,
    private val finalizeRecordingTrackAfterSuccessfulEngineStop: (String, Long) -> Unit,
) {

    /** Full user / lifecycle transport stop — same sequencing as legacy [ProjectViewModel.performTransportStopSequence]. */
    suspend fun stopAll() {
        playbackSession.cancelCompletionMonitorForTransportStop()

        recordingSession.clearStartupFlagForTransportStop()

        val activeRecordingTrackId = recordingSession.activeRecordingTrackIdForTransport()
        val recordingStopped =
            withAudioIo(dispatchers, "AudioController.stopRecording") {
                if (activeRecordingTrackId != null) {
                    audioController.stopRecording()
                } else {
                    false
                }
            }
        val firstSampleTransportPositionMs =
            if (activeRecordingTrackId != null && recordingStopped) {
                withAudioIo(dispatchers, "AudioController.recordingFirstSampleTransportPositionMs") {
                    audioController.recordingFirstSampleTransportPositionMs()
                }
            } else {
                AudioController.RecordingFirstSampleTransportUnset
            }
        if (activeRecordingTrackId != null && recordingStopped) {
            finalizeRecordingTrackAfterSuccessfulEngineStop(
                activeRecordingTrackId,
                firstSampleTransportPositionMs,
            )
        }

        playbackSession.stopEngineIfMarkedPlaying()

        recordingSession.clearRecordingTransportMarkers()
        playbackSession.clearPlayingTransportState()
    }

    /** Playback markers only — used when navigating to another project ([ProjectViewModel.bind]). */
    fun resetPlaybackForProjectChange() {
        playbackSession.resetWhenProjectChanges()
    }

    /** Stops native playback and clears playing markers without touching recording. */
    suspend fun pausePlayback() {
        playbackSession.cancelCompletionMonitorForTransportStop()
        playbackSession.stopEngineIfMarkedPlaying()
        playbackSession.clearPlayingTransportState()
    }

    /** Transport Seek.1: pause audio only; session lane maps and selection stay armed. */
    suspend fun pauseEnginePreservingSession() {
        playbackSession.pauseEnginePreservingSession()
    }
}
