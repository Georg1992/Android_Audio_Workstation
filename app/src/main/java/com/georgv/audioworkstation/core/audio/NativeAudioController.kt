package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.checkNotMainThreadForNativeLifecycle
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.engine.NativeEngine
import com.georgv.audioworkstation.engine.NativeMixdownStatus
import com.georgv.audioworkstation.ui.diagnostics.ThreadingDiagnostics
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
    private val audioFilePathProvider: AudioFilePathProvider,
    private val dispatchers: AppDispatchers,
) : AudioController {

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var recordingLevelJob: Job? = null

    private val _playbackState = MutableStateFlow(false)
    override val playbackState: StateFlow<Boolean> = _playbackState.asStateFlow()
    private val _recordingInputLevel = MutableStateFlow(0f)
    override val recordingInputLevel: StateFlow<Float> = _recordingInputLevel.asStateFlow()

    override fun readMasterPeakHoldLinear(): Float {
        checkNotMainThreadForNativeLifecycle("readMasterPeakHoldLinear")
        return nativeEngine.masterPeakHoldLinear()
    }

    override fun resetMasterPeakHold() {
        checkNotMainThreadForNativeLifecycle("resetMasterPeakHold")
        nativeEngine.resetMasterPeakHold()
    }

    override fun transportPositionMs(): Long {
        checkNotMainThreadForNativeLifecycle("transportPositionMs")
        return nativeEngine.transportPositionMs()
    }

    override fun recordingFirstSampleTransportPositionMs(): Long {
        checkNotMainThreadForNativeLifecycle("recordingFirstSampleTransportPositionMs")
        return nativeEngine.recordingFirstSampleTransportPositionMs()
    }

    override fun isPlaybackEngineRunning(): Boolean {
        checkNotMainThreadForNativeLifecycle("isPlaybackEngineRunning")
        return nativeEngine.isPlaybackActive()
    }

    override fun startRecording(spec: RecordingSpec, outputPath: String?): String? {
        checkNotMainThreadForNativeLifecycle("startRecording")
        ThreadingDiagnostics.logWorkBoundary("NativeAudioController.startRecording", phase = "entered")
        val resolvedPath =
            outputPath ?: audioFilePathProvider.trackOutputPath(spec.projectId, spec.trackId)
                ?: return null
        val request = spec.toRecordingRequest(resolvedPath)
        ThreadingDiagnostics.logWorkBoundary("NativeAudioController.startRecording", phase = "beforeJni")
        return resolvedPath.takeIf {
            val started = nativeEngine.startRecording(request)
            ThreadingDiagnostics.logWorkBoundary("NativeAudioController.startRecording", phase = "afterJni")
            if (started) monitorRecordingInputLevel()
            started
        }
    }

    override fun stopRecording(): Boolean {
        checkNotMainThreadForNativeLifecycle("stopRecording")
        recordingLevelJob?.cancel()
        recordingLevelJob = null
        val ok = nativeEngine.stopRecording()
        _recordingInputLevel.value = 0f
        return ok
    }

    override fun startPlayback(spec: MultiPlaybackSpec): Boolean {
        checkNotMainThreadForNativeLifecycle("startPlayback")
        val started = nativeEngine.startMultiPlayback(spec)
        if (started) monitorPlaybackCompletion()
        return started
    }

    override fun setPlaybackLaneGain(laneIndex: Int, gain: Float) {
        nativeEngine.setPlaybackLaneGain(laneIndex, gain)
    }

    override fun setPlaybackLanePan(laneIndex: Int, pan: Float) {
        nativeEngine.setPlaybackLanePan(laneIndex, pan)
    }

    override fun setArmedPlaybackLaneAudibility(audibleByLaneIndex: BooleanArray) {
        checkNotMainThreadForNativeLifecycle("setArmedPlaybackLaneAudibility")
        audibleByLaneIndex.forEachIndexed { laneIndex, audible ->
            nativeEngine.setPlaybackLaneAudible(laneIndex, audible)
        }
    }

    override fun setPlaybackLaneAudible(laneIndex: Int, audible: Boolean) {
        checkNotMainThreadForNativeLifecycle("setPlaybackLaneAudible")
        nativeEngine.setPlaybackLaneAudible(laneIndex, audible)
    }

    override fun beginHotJoinLane(
        wavFilePath: String,
        gain: Float,
        timelineClipStartMs: Long,
        timelineClipDurationMs: Long,
        loopEnabled: Boolean,
        loopSourceStartMs: Long,
        loopSourceEndMs: Long,
        pan: Float,
    ): Int {
        checkNotMainThreadForNativeLifecycle("beginHotJoinLane")
        return nativeEngine.beginHotJoinLane(
            wavFilePath,
            gain,
            timelineClipStartMs,
            timelineClipDurationMs,
            loopEnabled,
            loopSourceStartMs,
            loopSourceEndMs,
            pan,
        )
    }

    override fun cancelHotJoinLane(laneIndex: Int) {
        checkNotMainThreadForNativeLifecycle("cancelHotJoinLane")
        nativeEngine.cancelHotJoinLane(laneIndex)
    }

    override fun playbackLaneLifecycle(laneIndex: Int): PlaybackLaneLifecycle {
        checkNotMainThreadForNativeLifecycle("playbackLaneLifecycle")
        return nativeEngine.playbackLaneLifecycle(laneIndex)
    }

    override fun stopPlayback(): Boolean {
        checkNotMainThreadForNativeLifecycle("stopPlayback")
        monitorJob?.cancel()
        monitorJob = null
        val ok = nativeEngine.stopPlayback()
        _playbackState.value = false
        return ok
    }

    override fun release() {
        checkNotMainThreadForNativeLifecycle("release")
        monitorJob?.cancel()
        monitorJob = null
        recordingLevelJob?.cancel()
        recordingLevelJob = null
        _playbackState.value = false
        _recordingInputLevel.value = 0f
        nativeEngine.releaseEngine()
    }

    override suspend fun renderOfflineMixdown(
        spec: MultiPlaybackSpec,
        outputPath: String,
        onProgress: (Float) -> Unit,
    ): MixdownResult =
        withAudioIo(dispatchers, "renderOfflineMixdown") {
            checkNotMainThreadForNativeLifecycle("renderOfflineMixdown")
            when (nativeEngine.renderOfflineMixdown(spec, outputPath, onProgress)) {
                NativeMixdownStatus.Success -> MixdownResult.Success(outputPath)
                NativeMixdownStatus.Cancelled -> MixdownResult.Cancelled
                NativeMixdownStatus.Failed -> MixdownResult.Failed
            }
        }

    override fun cancelOfflineMixdown() {
        nativeEngine.cancelOfflineMixdown()
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
