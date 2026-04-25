package com.georgv.audioworkstation.core.audio

import kotlinx.coroutines.flow.StateFlow

interface AudioController {
    /**
     * Reactive flag tracking whether the engine is currently producing playback audio.
     * Flips to `true` synchronously inside [startPlayback] when it succeeds and back to
     * `false` once the engine reports completion or [stopPlayback] is called.
     */
    val playbackState: StateFlow<Boolean>

    fun startRecording(spec: RecordingSpec): String?
    fun stopRecording(): Boolean
    fun startPlayback(spec: PlaybackSpec): Boolean
    fun setPlaybackGain(gain: Float)
    fun stopPlayback(): Boolean
}
