package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.core.audio.CapturePort
import com.georgv.audioworkstation.core.audio.RecordingStopSnapshot
import com.georgv.audioworkstation.core.audio.capability.LiveSessionProfiling
import com.georgv.audioworkstation.core.audio.latency.LiveSessionLatencySnapshot
import com.georgv.audioworkstation.core.diagnostics.TransportFrameDiagnostics
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
 * 3. If a recording row was active (non-null id) and [CapturePort.stopRecording] succeeds, invoke finalize callback.
 * 4. If playback was marked active, [PlaybackPort.stopPlayback].
 * 5. Clear recording markers ([RecordingSessionController.clearRecordingTransportMarkers]).
 * 6. Clear playing markers ([PlaybackSessionController.clearPlayingTransportState]).
 */
class ProjectTransportController(
    private val capture: CapturePort,
    private val playbackSession: PlaybackSessionController,
    private val recordingSession: RecordingSessionController,
    private val dispatchers: AppDispatchers,
    private val finalizeRecordingTrackAfterSuccessfulEngineStop: (String, RecordingStopSnapshot) -> Unit,
    private val onLiveOverdubSessionEnd: suspend (LiveSessionLatencySnapshot) -> Unit,
) {

    /** Full user / lifecycle transport stop. */
    suspend fun stopAll() {
        playbackSession.cancelCompletionMonitorForTransportStop()

        recordingSession.clearStartupFlagForTransportStop()

        val activeRecordingTrackId = recordingSession.activeRecordingTrackIdForTransport()
        val liveOverdubSession = recordingSession.activeNormalOverdubContext() != null
        val liveSessionCapture =
            if (activeRecordingTrackId != null &&
                liveOverdubSession &&
                LiveSessionProfiling.captureOnOverdubEnd
            ) {
                withAudioIo(dispatchers, "CapturePort.captureLiveSessionLatencySnapshot") {
                    capture.captureLiveSessionLatencySnapshot()
                }
            } else {
                null
            }
        val recordingStopped =
            withAudioIo(dispatchers, "CapturePort.stopRecording") {
                if (activeRecordingTrackId != null) {
                    capture.stopRecording()
                } else {
                    false
                }
            }
        val stopSnapshot =
            if (activeRecordingTrackId != null && recordingStopped) {
                withAudioIo(dispatchers, "CapturePort.readRecordingStopSnapshot") {
                    capture.readRecordingStopSnapshot()
                }.also { snapshot ->
                    TransportFrameDiagnostics.logRecordingStop(snapshot)
                }
            } else {
                RecordingStopSnapshot(
                    firstSampleTransportPositionMs = CapturePort.RecordingFirstSampleTransportUnset,
                    capturedFrameCount = 0L,
                    capturedDurationMs = 0L,
                )
            }
        if (activeRecordingTrackId != null && recordingStopped) {
            finalizeRecordingTrackAfterSuccessfulEngineStop(
                activeRecordingTrackId,
                stopSnapshot,
            )
            if (liveSessionCapture != null) {
                onLiveOverdubSessionEnd(liveSessionCapture)
            }
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
