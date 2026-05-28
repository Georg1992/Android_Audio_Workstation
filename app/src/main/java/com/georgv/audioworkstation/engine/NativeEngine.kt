package com.georgv.audioworkstation.engine

import com.georgv.audioworkstation.core.audio.MultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.PlaybackLaneLifecycle
import com.georgv.audioworkstation.core.audio.PlaybackSpec
import com.georgv.audioworkstation.core.audio.RecordingRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeEngine @Inject constructor() {

    fun startRecording(request: RecordingRequest): Boolean =
        nativeStartRecording(
            sampleRate = request.sampleRate,
            fileBitDepth = request.fileBitDepth,
            channelMode = request.channelMode.ordinal,
            outputPath = request.outputPath,
            startPositionMs = request.timelineStartOffsetMs,
        )

    fun stopRecording(): Boolean = nativeStopRecording()

    fun recordingInputLevel(): Float = nativeGetRecordingInputLevel().coerceIn(0f, 1f)

    fun startPlayback(spec: PlaybackSpec): Boolean =
        nativeStartPlayback(
            sampleRate = spec.sampleRate,
            wavPath = spec.wavFilePath,
            gain = spec.gain,
            startPositionMs = spec.startPositionMs,
            sessionTimelineEndMs = spec.sessionTimelineEndMs,
        )

    fun startMultiPlayback(spec: MultiPlaybackSpec): Boolean =
        nativeStartMultiPlayback(
            sampleRate = spec.sampleRate,
            wavPaths = spec.lanes.map { it.wavFilePath }.toTypedArray(),
            gains = spec.lanes.map { it.gain }.toFloatArray(),
            startPositionMs = spec.startPositionMs,
            sessionTimelineEndMs = spec.sessionTimelineEndMs,
            laneClipStartMs = spec.lanes.map { it.timelineClipStartMs }.toLongArray(),
            laneClipDurationMs = spec.lanes.map { it.timelineClipDurationMs }.toLongArray(),
            laneLoopEnabled = spec.lanes.map { it.loopEnabled }.toBooleanArray(),
            laneLoopSourceStartMs = spec.lanes.map { it.loopSourceStartMs }.toLongArray(),
            laneLoopSourceEndMs = spec.lanes.map { it.loopSourceEndMs }.toLongArray(),
        )

    fun setPlaybackGain(gain: Float) {
        nativeSetPlaybackGain(gain)
    }

    fun setPlaybackLaneGain(laneIndex: Int, gain: Float) {
        nativeSetPlaybackLaneGain(laneIndex, gain.coerceIn(0f, 1f))
    }

    fun setPlaybackLaneAudible(laneIndex: Int, audible: Boolean) {
        nativeSetPlaybackLaneAudible(laneIndex, audible)
    }

    fun beginHotJoinLane(
        wavFilePath: String,
        gain: Float,
        timelineClipStartMs: Long = 0L,
        timelineClipDurationMs: Long = 0L,
    ): Int =
        nativeBeginHotJoinLane(
            wavFilePath,
            gain,
            timelineClipStartMs,
            timelineClipDurationMs,
        )

    fun cancelHotJoinLane(laneIndex: Int) {
        nativeCancelHotJoinLane(laneIndex)
    }

    fun playbackLaneLifecycle(laneIndex: Int): PlaybackLaneLifecycle =
        PlaybackLaneLifecycle.entries[nativeGetPlaybackLaneLifecycle(laneIndex).toInt()]

    fun isPlaybackActive(): Boolean = nativeIsPlaybackActive()

    /**
     * Sample-domain transport timeline frame (Clock.2).
     * Advanced by playback render while active; does not drive UI playhead until Clock.4.
     */
    fun transportFrame(): Long = nativeGetTransportFrame()

    /** Transport origin frame from last playback arm. */
    fun transportStartFrame(): Long = nativeGetTransportStartFrame()

    /** [transportFrame] as milliseconds at project sample rate (same integer math as native). */
    fun transportPositionMs(): Long = nativeGetTransportPositionMs()

    fun stopPlayback(): Boolean = nativeStopPlayback()

    /**
     * Tears down the streaming engine: joins the I/O thread, closes the WAV
     * source and the persistent Oboe output stream. Called when the project
     * screen is disposed so we don't keep the audio device awake in the
     * background.
     */
    fun releaseEngine() {
        nativeReleaseEngine()
    }

    private external fun nativeStartRecording(
        sampleRate: Int,
        fileBitDepth: Int,
        channelMode: Int,
        outputPath: String,
        startPositionMs: Long,
    ): Boolean

    private external fun nativeStopRecording(): Boolean

    private external fun nativeGetRecordingInputLevel(): Float

    private external fun nativeStartPlayback(
        sampleRate: Int,
        wavPath: String,
        gain: Float,
        startPositionMs: Long,
        sessionTimelineEndMs: Long,
    ): Boolean

    private external fun nativeStartMultiPlayback(
        sampleRate: Int,
        wavPaths: Array<String>,
        gains: FloatArray,
        startPositionMs: Long,
        sessionTimelineEndMs: Long,
        laneClipStartMs: LongArray,
        laneClipDurationMs: LongArray,
        laneLoopEnabled: BooleanArray,
        laneLoopSourceStartMs: LongArray,
        laneLoopSourceEndMs: LongArray,
    ): Boolean

    private external fun nativeSetPlaybackGain(gain: Float)

    private external fun nativeSetPlaybackLaneGain(laneIndex: Int, gain: Float)

    private external fun nativeSetPlaybackLaneAudible(laneIndex: Int, audible: Boolean)

    private external fun nativeBeginHotJoinLane(
        wavFilePath: String,
        gain: Float,
        timelineClipStartMs: Long,
        timelineClipDurationMs: Long,
    ): Int

    private external fun nativeCancelHotJoinLane(laneIndex: Int)

    private external fun nativeGetPlaybackLaneLifecycle(laneIndex: Int): Int

    private external fun nativeIsPlaybackActive(): Boolean

    private external fun nativeGetTransportFrame(): Long

    private external fun nativeGetTransportStartFrame(): Long

    private external fun nativeGetTransportPositionMs(): Long

    private external fun nativeStopPlayback(): Boolean

    private external fun nativeReleaseEngine()

    private companion object {
        init {
            System.loadLibrary("audioworkstation")
        }
    }
}
