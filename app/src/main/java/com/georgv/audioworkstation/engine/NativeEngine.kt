package com.georgv.audioworkstation.engine

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
            outputPath = request.outputPath
        )

    fun stopRecording(): Boolean = nativeStopRecording()

    fun startPlayback(spec: PlaybackSpec): Boolean =
        nativeStartPlayback(
            sampleRate = spec.sampleRate,
            wavPath = spec.wavFilePath,
            gain = spec.gain
        )

    fun setPlaybackGain(gain: Float) {
        nativeSetPlaybackGain(gain)
    }

    fun isPlaybackActive(): Boolean = nativeIsPlaybackActive()

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
        outputPath: String
    ): Boolean

    private external fun nativeStopRecording(): Boolean

    private external fun nativeStartPlayback(
        sampleRate: Int,
        wavPath: String,
        gain: Float
    ): Boolean

    private external fun nativeSetPlaybackGain(gain: Float)

    private external fun nativeIsPlaybackActive(): Boolean

    private external fun nativeStopPlayback(): Boolean

    private external fun nativeReleaseEngine()

    private companion object {
        init {
            System.loadLibrary("audioworkstation")
        }
    }
}
