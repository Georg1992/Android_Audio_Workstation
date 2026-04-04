package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.engine.NativeEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeAudioController @Inject constructor(
    private val nativeEngine: NativeEngine,
    private val audioFilePathProvider: AudioFilePathProvider
) : AudioController {

    override fun startRecording(spec: RecordingSpec): String? {
        val outputPath = audioFilePathProvider.recordingOutputPath(spec.projectId, spec.trackId) ?: return null
        val request = spec.toRecordingRequest(outputPath)
        return outputPath.takeIf { nativeEngine.startRecording(request) }
    }

    override fun stopRecording(): Boolean = nativeEngine.stopRecording()

    override fun startPlayback(spec: PlaybackSpec): Boolean = nativeEngine.startPlayback(spec)

    override fun setPlaybackGain(gain: Float) {
        nativeEngine.setPlaybackGain(gain)
    }

    override fun isPlaybackActive(): Boolean = nativeEngine.isPlaybackActive()

    override fun stopPlayback(): Boolean = nativeEngine.stopPlayback()

    override fun release() {
        nativeEngine.release()
    }
}
