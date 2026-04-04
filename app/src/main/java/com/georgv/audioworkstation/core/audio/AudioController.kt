package com.georgv.audioworkstation.core.audio

interface AudioController {
    fun startRecording(spec: RecordingSpec): String?
    fun stopRecording(): Boolean
    fun startPlayback(spec: PlaybackSpec): Boolean
    fun setPlaybackGain(gain: Float)
    fun isPlaybackActive(): Boolean
    fun stopPlayback(): Boolean
    fun release()
}
