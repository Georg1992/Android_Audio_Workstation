package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.audio.AudioRouteKeySource
import com.georgv.audioworkstation.core.audio.latency.LiveSessionLatencySnapshot
import com.georgv.audioworkstation.core.audio.latency.latencyMsToNs
import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.checkNotMainThreadForNativeLifecycle
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.engine.NativeEngine
import com.georgv.audioworkstation.engine.NativeMixdownStatus
import com.georgv.audioworkstation.core.diagnostics.ThreadingDiagnostics
import com.georgv.audioworkstation.core.diagnostics.PlaybackStartupTrace
import com.georgv.audioworkstation.core.diagnostics.TransportFrameDiagnostics
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
 * JNI-backed playback, capture, mixdown, and meter ports. After [startPlayback] succeeds,
 * [playbackState] is updated from a background poll of [NativeEngine.isPlaybackActive].
 */
@Singleton
class NativeAudioController @Inject constructor(
    private val nativeEngine: NativeEngine,
    private val routeKeySource: AudioRouteKeySource,
    private val audioFilePathProvider: AudioFilePathProvider,
    private val dispatchers: AppDispatchers,
    private val sessionTransportGate: SessionTransportCapabilityGate,
) : PlaybackPort, CapturePort, MixdownPort, MeterPort {

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

    override fun recordingFirstSampleTransportFrame(): Long {
        checkNotMainThreadForNativeLifecycle("recordingFirstSampleTransportFrame")
        return nativeEngine.recordingFirstSampleTransportFrame()
    }

    override fun transportStartFrame(): Long {
        checkNotMainThreadForNativeLifecycle("transportStartFrame")
        return nativeEngine.transportStartFrame()
    }

    override fun transportFrame(): Long {
        checkNotMainThreadForNativeLifecycle("transportFrame")
        return nativeEngine.transportFrame()
    }

    override fun recordingCapturedFrameCount(): Long {
        checkNotMainThreadForNativeLifecycle("recordingCapturedFrameCount")
        return nativeEngine.recordingCapturedFrameCount()
    }

    override fun recordingCapturedDurationMs(): Long {
        checkNotMainThreadForNativeLifecycle("recordingCapturedDurationMs")
        return nativeEngine.recordingCapturedDurationMs()
    }

    override fun sessionPerceivedPlaybackOffsetMs(): Long {
        checkNotMainThreadForNativeLifecycle("sessionPerceivedPlaybackOffsetMs")
        return nativeEngine.sessionPerceivedPlaybackOffsetMs()
    }

    override fun readRecordingStopSnapshot(): RecordingStopSnapshot =
        RecordingStopSnapshot(
            firstSampleTransportPositionMs = recordingFirstSampleTransportPositionMs(),
            capturedFrameCount = recordingCapturedFrameCount(),
            capturedDurationMs = recordingCapturedDurationMs(),
            sessionPerceivedPlaybackOffsetMs = sessionPerceivedPlaybackOffsetMs(),
        )

    override fun liveOutputLatencyNs(): Long {
        checkNotMainThreadForNativeLifecycle("liveOutputLatencyNs")
        return nativeEngine.liveOutputLatencyNs()
    }

    override fun configureSessionTransportLatencies(
        inputLatencyMs: Double,
        outputLatencyMs: Double,
    ) {
        checkNotMainThreadForNativeLifecycle("configureSessionTransportLatencies")
        nativeEngine.setSessionTransportLatenciesNs(
            latencyMsToNs(inputLatencyMs),
            latencyMsToNs(outputLatencyMs),
        )
    }

    private fun ensureSessionTransportPrepared(sampleRate: Int) {
        sessionTransportGate.ensurePreparedForSampleRate(sampleRate)
    }

    override fun startOverdubRecordingSession(
        playbackSpec: MultiPlaybackSpec,
        recordingSpec: RecordingSpec,
        outputPath: String,
    ): String? {
        checkNotMainThreadForNativeLifecycle("startOverdubRecordingSession")
        ensureSessionTransportPrepared(playbackSpec.sampleRate)
        ThreadingDiagnostics.logWorkBoundary("NativeAudioController.startOverdubRecordingSession", phase = "beforeJni")
        PlaybackStartupTrace.logJniBoundary(path = "overdub_session", phase = "before_jni")
        val started =
            nativeEngine.startOverdubRecordingSession(
                playbackSpec = playbackSpec,
                recordingSpec = recordingSpec,
                outputPath = outputPath,
                inputRouteKey = routeKeySource.routeKey(playbackSpec.sampleRate),
            )
        PlaybackStartupTrace.logJniBoundary(path = "overdub_session", phase = "after_jni")
        ThreadingDiagnostics.logWorkBoundary("NativeAudioController.startOverdubRecordingSession", phase = "afterJni")
        if (!started) return null
        TransportFrameDiagnostics.logOverdubSessionArm(playbackSpec)
        monitorPlaybackCompletion(waitForActiveFirst = true)
        monitorRecordingInputLevel()
        return outputPath
    }

    override fun isPlaybackEngineRunning(): Boolean {
        checkNotMainThreadForNativeLifecycle("isPlaybackEngineRunning")
        return nativeEngine.isPlaybackActive()
    }

    override fun startRecording(spec: RecordingSpec, outputPath: String?): String? {
        checkNotMainThreadForNativeLifecycle("startRecording")
        ensureSessionTransportPrepared(spec.sampleRate)
        ThreadingDiagnostics.logWorkBoundary("NativeAudioController.startRecording", phase = "entered")
        val resolvedPath =
            outputPath ?: audioFilePathProvider.trackOutputPath(spec.projectId, spec.trackId)
                ?: return null
        val request = spec.toRecordingRequest(resolvedPath)
        ThreadingDiagnostics.logWorkBoundary("NativeAudioController.startRecording", phase = "beforeJni")
        return resolvedPath.takeIf {
            val started = nativeEngine.startRecording(request)
            ThreadingDiagnostics.logWorkBoundary("NativeAudioController.startRecording", phase = "afterJni")
            if (started) {
                monitorRecordingInputLevel()
            }
            started
        }
    }

    override fun captureLiveSessionLatencySnapshot(): LiveSessionLatencySnapshot {
        checkNotMainThreadForNativeLifecycle("captureLiveSessionLatencySnapshot")
        return LiveSessionLatencySnapshot(
            outputProbe = nativeEngine.probeOutputStreamCapability(),
            inputProbe = nativeEngine.probeInputStreamCapability(),
            outputCallbackCost = nativeEngine.outputCallbackCostSnapshot(),
            inputLoopCost = nativeEngine.inputLoopCostSnapshot(),
        )
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
        ensureSessionTransportPrepared(spec.sampleRate)
        PlaybackStartupTrace.logJniBoundary(path = "multi_playback", phase = "before_jni")
        val started = nativeEngine.startMultiPlayback(spec)
        PlaybackStartupTrace.logJniBoundary(path = "multi_playback", phase = "after_jni")
        if (started) {
            TransportFrameDiagnostics.logPlaybackArm(
                spec = spec,
                transportStartFrame = nativeEngine.transportStartFrame(),
                transportFrame = nativeEngine.transportFrame(),
            )
            monitorPlaybackCompletion()
        }
        return started
    }

    override fun rearmOverdubPlaybackDuringRecording(spec: MultiPlaybackSpec): Boolean {
        checkNotMainThreadForNativeLifecycle("rearmOverdubPlaybackDuringRecording")
        ensureSessionTransportPrepared(spec.sampleRate)
        PlaybackStartupTrace.logJniBoundary(path = "rearm_overdub", phase = "before_jni")
        val started = nativeEngine.rearmOverdubPlaybackDuringRecording(spec)
        PlaybackStartupTrace.logJniBoundary(path = "rearm_overdub", phase = "after_jni")
        if (started) {
            TransportFrameDiagnostics.logPlaybackArm(
                spec = spec,
                transportStartFrame = nativeEngine.transportStartFrame(),
                transportFrame = nativeEngine.transportFrame(),
            )
            monitorPlaybackCompletion()
        }
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
        sourceTrimStartMs: Long,
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
            sourceTrimStartMs,
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

    private fun monitorPlaybackCompletion(waitForActiveFirst: Boolean = false) {
        monitorJob?.cancel()
        monitorJob =
            monitorScope.launch {
                if (waitForActiveFirst) {
                    while (!nativeEngine.isPlaybackActive()) {
                        delay(POLL_INTERVAL_MS)
                    }
                }
                _playbackState.value = true
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
