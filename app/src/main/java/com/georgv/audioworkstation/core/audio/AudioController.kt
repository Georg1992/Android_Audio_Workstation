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

    /**
     * Releases the underlying audio engine: tears down the persistent output
     * stream, joins the streaming I/O thread, and closes any open audio
     * source. Call when the project screen is disposed so the audio device
     * isn't kept awake in the background.
     */
    fun release()
}
