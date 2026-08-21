package com.georgv.audioworkstation.core.audio

import kotlinx.coroutines.flow.StateFlow

/** Live multi-lane playback, hot-join, and engine teardown. */
interface PlaybackPort {
    val playbackState: StateFlow<Boolean>

    fun isPlaybackEngineRunning(): Boolean

    fun startPlayback(spec: MultiPlaybackSpec): Boolean

    fun stopPlayback(): Boolean

    fun setPlaybackLaneGain(laneIndex: Int, gain: Float)

    fun setPlaybackLanePan(laneIndex: Int, pan: Float)

    fun setArmedPlaybackLaneAudibility(audibleByLaneIndex: BooleanArray)

    fun setPlaybackLaneAudible(laneIndex: Int, audible: Boolean)

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

    fun cancelHotJoinLane(laneIndex: Int)

    fun playbackLaneLifecycle(laneIndex: Int): PlaybackLaneLifecycle

    fun rearmOverdubPlaybackDuringRecording(spec: MultiPlaybackSpec): Boolean

    /**
     * Tears down the persistent output stream and native engine.
     * Call when the last project screen is disposed.
     */
    fun release()
}
