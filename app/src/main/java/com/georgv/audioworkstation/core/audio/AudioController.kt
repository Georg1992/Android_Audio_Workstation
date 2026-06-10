@file:Suppress("TooManyFunctions")

package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.audio.latency.LiveSessionLatencySnapshot
import com.georgv.audioworkstation.core.audio.RecordingStopSnapshot.Companion.SessionPerceivedPlaybackOffsetUnset
import kotlinx.coroutines.flow.StateFlow

interface AudioController {    /**
     * Reactive flag tracking whether the engine is currently producing playback audio.
     * Flips to `true` synchronously inside [startPlayback] when it succeeds and back to
     * `false` once the engine reports completion or [stopPlayback] is called.
     */
    val playbackState: StateFlow<Boolean>

    /** Latest normalized recording input level for lightweight UI metering, in 0f..1f. */
    val recordingInputLevel: StateFlow<Float>

    /** Reads the native session max master peak (linear, pre soft-clip). */
    fun readMasterPeakHoldLinear(): Float

    /**
     * Clears native session peak-hold. Playback and transport are unchanged.
     * UI should also reset its displayed peak after calling this.
     */
    fun resetMasterPeakHold()

    /** Absolute timeline position in ms from the native transport clock (Clock.2+). */
    fun transportPositionMs(): Long

    /**
     * Timeline ms when the first input sample was captured on the last recording session.
     * [RecordingFirstSampleTransportUnset] until a non-empty capture occurred.
     */
    fun recordingFirstSampleTransportPositionMs(): Long = RecordingFirstSampleTransportUnset

    fun recordingFirstSampleTransportFrame(): Long = RecordingFirstSampleTransportUnset

    fun transportStartFrame(): Long = 0L

    fun transportFrame(): Long = 0L

    fun recordingCapturedFrameCount(): Long = 0L

    fun recordingCapturedDurationMs(): Long = 0L

    /**
     * Transport stamp and PCM-derived duration from the last recording session.
     * Call immediately after a successful [stopRecording].
     */
    fun readRecordingStopSnapshot(): RecordingStopSnapshot =
        RecordingStopSnapshot(
            firstSampleTransportPositionMs = recordingFirstSampleTransportPositionMs(),
            capturedFrameCount = recordingCapturedFrameCount(),
            capturedDurationMs = recordingCapturedDurationMs(),
            sessionPerceivedPlaybackOffsetMs = sessionPerceivedPlaybackOffsetMs(),
        )

    fun sessionPerceivedPlaybackOffsetMs(): Long = SessionPerceivedPlaybackOffsetUnset

    /**
     * Live HAL output latency (ns) from the running playback stream.
     * [PlaybackTransportSync.LiveOutputLatencyUnsetNs] when invalid.
     */
    fun liveOutputLatencyNs(): Long = PlaybackTransportSync.LiveOutputLatencyUnsetNs

    /**
     * Probe open streams while a recording session is still active (call before [stopRecording]).
     */
    fun captureLiveSessionLatencySnapshot(): LiveSessionLatencySnapshot =
        LiveSessionLatencySnapshot.EMPTY

    /**
     * Atomic play+record: arms overdub lanes, opens input capture, and starts playback immediately.
     */
    fun startOverdubRecordingSession(
        playbackSpec: MultiPlaybackSpec,
        recordingSpec: RecordingSpec,
        outputPath: String,
    ): String? = null

    /**
     * Low-level native hint API. Prefer [com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate]
     * for live sessions so profile resolution and apply stay in one place.
     */
    fun configureSessionTransportLatencies(
        inputLatencyMs: Double = 0.0,
        outputLatencyMs: Double = 0.0,
    ) = Unit

    /** True when the native engine is actively playing (same signal as [playbackState] when in sync). */
    fun isPlaybackEngineRunning(): Boolean

    /**
     * Rebuild overdub backing lanes during an active recording session without resetting the
     * overdub capture anchor.
     */
    fun rearmOverdubPlaybackDuringRecording(spec: MultiPlaybackSpec): Boolean = false

    companion object {
        /** Sentinel from native when no input samples were captured. */
        const val RecordingFirstSampleTransportUnset = -1L
    }

    fun startRecording(spec: RecordingSpec, outputPath: String? = null): String?
    fun stopRecording(): Boolean
    fun startPlayback(spec: MultiPlaybackSpec): Boolean
    fun setPlaybackLaneGain(laneIndex: Int, gain: Float)
    fun setPlaybackLanePan(laneIndex: Int, pan: Float)

    /**
     * Live audibility for lanes already armed in the current session (HJ.1). Index matches
     * [MultiPlaybackSpec.lanes] order from the last successful [startPlayback].
     */
    fun setArmedPlaybackLaneAudibility(audibleByLaneIndex: BooleanArray)

    /** HJ.1/HJ.2: live mute for a single armed lane index. */
    fun setPlaybackLaneAudible(laneIndex: Int, audible: Boolean)

    /**
     * HJ.2: reserve a lane and begin async prepare+commit at [NativeEngine.transportFrame].
     * Returns lane index 0..[MultiPlaybackSpec.MaxLanes]-1, or -1 when no slot is available.
     */
    fun beginHotJoinLane(
        wavFilePath: String,
        gain: Float,
        timelineClipStartMs: Long = 0L,
        timelineClipDurationMs: Long = 0L,
        loopEnabled: Boolean = false,
        loopSourceStartMs: Long = 0L,
        loopSourceEndMs: Long = 0L,
        sourceTrimStartMs: Long = 0L,
        pan: Float = 0f,
    ): Int

    /** HJ.2: cancel [PlaybackLaneLifecycle.Preparing] / [ReadyToCommit] on [laneIndex]. */
    fun cancelHotJoinLane(laneIndex: Int)

    fun playbackLaneLifecycle(laneIndex: Int): PlaybackLaneLifecycle

    fun stopPlayback(): Boolean

    /**
     * Releases the underlying audio engine: tears down the persistent output
     * stream, joins the streaming I/O thread, and closes any open audio
     * source. Call when the project screen is disposed so the audio device
     * isn't kept awake in the background.
     */
    fun release()

    /**
     * Native offline bounce using the same mixer path as live playback.
     * Runs on [com.georgv.audioworkstation.core.coroutines.AppDispatchers.audioIo].
     */
    suspend fun renderOfflineMixdown(
        spec: MultiPlaybackSpec,
        outputPath: String,
        onProgress: (Float) -> Unit,
    ): MixdownResult = MixdownResult.Failed

    fun cancelOfflineMixdown() = Unit
}
