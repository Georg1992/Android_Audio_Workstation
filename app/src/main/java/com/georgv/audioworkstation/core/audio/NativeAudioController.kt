package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.diagnostics.RecordingLatencyTrace
import com.georgv.audioworkstation.engine.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeAudioController @Inject constructor(
    private val nativeEngine: NativeEngine,
    private val audioFilePathProvider: AudioFilePathProvider
) : AudioController {

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    private val _playbackState = MutableStateFlow(false)
    override val playbackState: StateFlow<Boolean> = _playbackState.asStateFlow()

    override fun startRecording(spec: RecordingSpec): String? {
        RecordingLatencyTrace.log("before_track_output_path")
        val outputPath = audioFilePathProvider.trackOutputPath(spec.projectId, spec.trackId) ?: run {
            RecordingLatencyTrace.log("track_output_path_unavailable")
            return null
        }
        RecordingLatencyTrace.log("after_track_output_path")
        val request = spec.toRecordingRequest(outputPath)
        RecordingLatencyTrace.log("before_native_start_recording_jni")
        val ok = nativeEngine.startRecording(request)
        RecordingLatencyTrace.log("after_native_start_recording_jni_ok_$ok")
        return outputPath.takeIf { ok }
    }

    override fun stopRecording(): Boolean = nativeEngine.stopRecording()

    override fun startPlayback(spec: PlaybackSpec): Boolean {
        val started = nativeEngine.startPlayback(spec)
        if (started) {
            _playbackState.value = true
            monitorJob?.cancel()
            // The native engine signals completion only via its `isPlaybackActive` flag, so we
            // poll it on a background dispatcher and flip the StateFlow. This keeps the polling
            // out of the ViewModel and lets all callers stay reactive.
            monitorJob = monitorScope.launch {
                while (nativeEngine.isPlaybackActive()) {
                    delay(POLL_INTERVAL_MS)
                }
                _playbackState.value = false
            }
        }
        return started
    }

    override fun setPlaybackGain(gain: Float) {
        nativeEngine.setPlaybackGain(gain)
    }

    override fun stopPlayback(): Boolean {
        monitorJob?.cancel()
        monitorJob = null
        val ok = nativeEngine.stopPlayback()
        _playbackState.value = false
        return ok
    }

    override fun release() {
        monitorJob?.cancel()
        monitorJob = null
        _playbackState.value = false
        nativeEngine.releaseEngine()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 50L
    }
}
