package com.georgv.audioworkstation.core.audio

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
import kotlin.math.pow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JNI-backed [AudioController]. After [startPlayback] succeeds, [playbackState] is updated from a
 * background poll of [NativeEngine.isPlaybackActive]. Screen code may also observe the same flow
 * for sequencing (for example playback completion)—keep behavior aligned if either side changes.
 */
@Singleton
class NativeAudioController @Inject constructor(
    private val nativeEngine: NativeEngine,
    private val audioFilePathProvider: AudioFilePathProvider
) : AudioController {

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var recordingLevelJob: Job? = null

    private val _playbackState = MutableStateFlow(false)
    override val playbackState: StateFlow<Boolean> = _playbackState.asStateFlow()
    private val _recordingInputLevel = MutableStateFlow(0f)
    override val recordingInputLevel: StateFlow<Float> = _recordingInputLevel.asStateFlow()

    override fun transportPositionMs(): Long = nativeEngine.transportPositionMs()

    override fun isPlaybackEngineRunning(): Boolean = nativeEngine.isPlaybackActive()

    override fun startRecording(spec: RecordingSpec, outputPath: String?): String? {
        val resolvedPath =
            outputPath ?: audioFilePathProvider.trackOutputPath(spec.projectId, spec.trackId)
                ?: return null
        val request = spec.toRecordingRequest(resolvedPath)
        return resolvedPath.takeIf {
            val started = nativeEngine.startRecording(request)
            if (started) monitorRecordingInputLevel()
            started
        }
    }

    override fun stopRecording(): Boolean {
        recordingLevelJob?.cancel()
        recordingLevelJob = null
        val ok = nativeEngine.stopRecording()
        _recordingInputLevel.value = 0f
        return ok
    }

    override fun startPlayback(spec: PlaybackSpec): Boolean {
        val started = nativeEngine.startPlayback(spec)
        if (started) monitorPlaybackCompletion()
        return started
    }

    override fun startPlayback(spec: MultiPlaybackSpec): Boolean {
        val started = nativeEngine.startMultiPlayback(spec)
        if (started) monitorPlaybackCompletion()
        return started
    }

    override fun setPlaybackGain(gain: Float) {
        nativeEngine.setPlaybackGain(gain)
    }

    override fun setPlaybackLaneGain(laneIndex: Int, gain: Float) {
        nativeEngine.setPlaybackLaneGain(laneIndex, gain)
    }

    override fun setArmedPlaybackLaneAudibility(audibleByLaneIndex: BooleanArray) {
        audibleByLaneIndex.forEachIndexed { laneIndex, audible ->
            nativeEngine.setPlaybackLaneAudible(laneIndex, audible)
        }
    }

    override fun setPlaybackLaneAudible(laneIndex: Int, audible: Boolean) {
        nativeEngine.setPlaybackLaneAudible(laneIndex, audible)
    }

    override fun beginHotJoinLane(
        wavFilePath: String,
        gain: Float,
        timelineClipStartMs: Long,
        timelineClipDurationMs: Long,
    ): Int =
        nativeEngine.beginHotJoinLane(
            wavFilePath,
            gain,
            timelineClipStartMs,
            timelineClipDurationMs,
        )

    override fun cancelHotJoinLane(laneIndex: Int) {
        nativeEngine.cancelHotJoinLane(laneIndex)
    }

    override fun playbackLaneLifecycle(laneIndex: Int): PlaybackLaneLifecycle =
        nativeEngine.playbackLaneLifecycle(laneIndex)

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
        recordingLevelJob?.cancel()
        recordingLevelJob = null
        _playbackState.value = false
        _recordingInputLevel.value = 0f
        nativeEngine.releaseEngine()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 50L
        const val RECORDING_LEVEL_POLL_INTERVAL_MS = 33L
        const val RECORDING_LEVEL_ATTACK = 0.88f
        const val RECORDING_LEVEL_RELEASE = 0.06f
        const val RECORDING_LEVEL_DISPLAY_GAIN = 2.0f
        const val RECORDING_LEVEL_DISPLAY_EXPONENT = 0.5f
    }

    private fun monitorPlaybackCompletion() {
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

    private fun monitorRecordingInputLevel() {
        recordingLevelJob?.cancel()
        recordingLevelJob = monitorScope.launch {
            var smoothed = 0f
            while (true) {
                val target = scaleRecordingLevelForDisplay(nativeEngine.recordingInputLevel())
                val coefficient =
                    if (target > smoothed) RECORDING_LEVEL_ATTACK else RECORDING_LEVEL_RELEASE
                smoothed += (target - smoothed) * coefficient
                _recordingInputLevel.value = smoothed.coerceIn(0f, 1f)
                delay(RECORDING_LEVEL_POLL_INTERVAL_MS)
            }
        }
    }

    private fun scaleRecordingLevelForDisplay(level: Float): Float {
        val boosted = (level.coerceIn(0f, 1f) * RECORDING_LEVEL_DISPLAY_GAIN).coerceIn(0f, 1f)
        if (boosted <= 0.0001f) return 0f
        return boosted.pow(RECORDING_LEVEL_DISPLAY_EXPONENT).coerceIn(0f, 1f)
    }
}
