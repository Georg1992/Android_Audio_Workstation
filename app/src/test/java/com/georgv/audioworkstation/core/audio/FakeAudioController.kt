package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.audio.latency.LiveSessionLatencySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal open class FakeAudioController(
    private val startRecordingPath: String? = "recordings/project-1/default.wav",
    private val stopRecordingResult: Boolean = true,
    private val startPlaybackResult: Boolean = true,
    private val stopPlaybackResult: Boolean = true,
    /**
     * Per-invocation gate for [startPlayback]. Index is 0-based across the test lifetime.
     * Return value is AND-ed with [startPlaybackResult] for both the return and [playbackState].
     */
    private val startPlaybackPermitted: (Int) -> Boolean = { _ -> true },
) : PlaybackPort, CapturePort, MixdownPort, MeterPort {
    var startPlaybackCalls = 0
        private set
    var rearmOverdubPlaybackCalls = 0
        private set
    val playbackLaneGainCalls = mutableListOf<Pair<Int, Float>>()
    var lastMultiPlaybackSpec: MultiPlaybackSpec? = null
        private set
    var lastRearmOverdubPlaybackSpec: MultiPlaybackSpec? = null
        private set
    var lastRecordingSpec: RecordingSpec? = null
        private set

    /** Test hook invoked at the beginning of native [startRecording] (before JNI work). */
    var onEnterStartRecording: (() -> Unit)? = null

    var stopRecordingCalls = 0
        private set
    var stopPlaybackCalls = 0
        private set
    private var startPlaybackInvocationIndex = 0
    private val _playbackState = MutableStateFlow(false)
    override val playbackState: StateFlow<Boolean> = _playbackState.asStateFlow()
    private val _recordingInputLevel = MutableStateFlow(0f)
    override val recordingInputLevel: StateFlow<Float> = _recordingInputLevel.asStateFlow()
    var masterPeakHoldLinearValue = 0f

    var transportPositionMsValue: Long = 0L
    var recordingFirstSampleTransportPositionMsValue: Long = CapturePort.RecordingFirstSampleTransportUnset
    var recordingCapturedFrameCountValue: Long = 0L
    var recordingCapturedDurationMsValue: Long = 0L
    var lastOverdubPlaybackSpec: MultiPlaybackSpec? = null
        private set

    override fun transportPositionMs(): Long = transportPositionMsValue

    override fun recordingFirstSampleTransportPositionMs(): Long =
        recordingFirstSampleTransportPositionMsValue

    override fun recordingCapturedFrameCount(): Long = recordingCapturedFrameCountValue

    override fun recordingCapturedDurationMs(): Long = recordingCapturedDurationMsValue

    override fun startOverdubRecordingSession(
        playbackSpec: MultiPlaybackSpec,
        recordingSpec: RecordingSpec,
        outputPath: String,
    ): String? {
        onEnterStartRecording?.invoke()
        lastRecordingSpec = recordingSpec
        lastOverdubPlaybackSpec = playbackSpec
        transportPositionMsValue = playbackSpec.startPositionMs
        if (startRecordingPath == null) return null
        val started = startPlayback(playbackSpec)
        if (!started) return null
        return outputPath
    }

    override fun isPlaybackEngineRunning(): Boolean = _playbackState.value

    override fun readMasterPeakHoldLinear(): Float = masterPeakHoldLinearValue

    override fun resetMasterPeakHold() {
        masterPeakHoldLinearValue = 0f
    }

    /** Simulates the engine reporting playback completion. */
    fun completePlayback() {
        _playbackState.value = false
    }

    fun emitRecordingInputLevel(level: Float) {
        _recordingInputLevel.value = level
    }

    fun setMasterPeakHoldLinear(linearPeak: Float) {
        masterPeakHoldLinearValue = linearPeak
    }

    override fun startRecording(spec: RecordingSpec, outputPath: String?): String? {
        onEnterStartRecording?.invoke()
        lastRecordingSpec = spec
        transportPositionMsValue = spec.timelineStartOffsetMs
        if (startRecordingPath == null) return null
        return outputPath ?: startRecordingPath.replace("default", spec.trackId)
    }

    override fun stopRecording(): Boolean {
        stopRecordingCalls += 1
        _recordingInputLevel.value = 0f
        if (recordingFirstSampleTransportPositionMsValue < 0L && lastRecordingSpec != null) {
            recordingFirstSampleTransportPositionMsValue = lastRecordingSpec!!.timelineStartOffsetMs
        }
        if (recordingCapturedDurationMsValue <= 0L && recordingCapturedFrameCountValue <= 0L) {
            recordingCapturedDurationMsValue =
                (recordingFirstSampleTransportPositionMsValue + 1_000L).coerceAtLeast(0L)
        }
        return stopRecordingResult
    }

    override fun startPlayback(spec: MultiPlaybackSpec): Boolean {
        startPlaybackCalls += 1
        lastMultiPlaybackSpec = spec
        transportPositionMsValue = spec.startPositionMs
        val permitted = startPlaybackPermitted(startPlaybackInvocationIndex++)
        val playing = permitted && startPlaybackResult
        _playbackState.value = playing
        return playing
    }

    override fun rearmOverdubPlaybackDuringRecording(spec: MultiPlaybackSpec): Boolean {
        rearmOverdubPlaybackCalls += 1
        lastRearmOverdubPlaybackSpec = spec
        transportPositionMsValue = spec.startPositionMs
        val permitted = startPlaybackPermitted(startPlaybackInvocationIndex++)
        val playing = permitted && startPlaybackResult
        _playbackState.value = playing
        return playing
    }

    override fun setPlaybackLaneGain(laneIndex: Int, gain: Float) {
        playbackLaneGainCalls.add(laneIndex to gain)
    }

    val playbackLanePanCalls = mutableListOf<Pair<Int, Float>>()

    override fun setPlaybackLanePan(laneIndex: Int, pan: Float) {
        playbackLanePanCalls.add(laneIndex to pan)
    }

    var lastArmedLaneAudibility: BooleanArray? = null
        private set
    var armedLaneAudibilityCalls = 0

    override fun setArmedPlaybackLaneAudibility(audibleByLaneIndex: BooleanArray) {
        armedLaneAudibilityCalls += 1
        lastArmedLaneAudibility = audibleByLaneIndex.copyOf()
    }

    val playbackLaneAudibleCalls = mutableListOf<Pair<Int, Boolean>>()

    override fun setPlaybackLaneAudible(laneIndex: Int, audible: Boolean) {
        playbackLaneAudibleCalls.add(laneIndex to audible)
    }

    var beginHotJoinCalls = 0
        private set
    var lastHotJoinWavPath: String? = null
        private set
    var lastHotJoinGain: Float? = null
        private set
    var lastHotJoinClipStartMs: Long? = null
        private set
    var lastHotJoinClipDurationMs: Long? = null
        private set
    var lastHotJoinLoopEnabled: Boolean? = null
        private set
    var lastHotJoinLoopSourceStartMs: Long? = null
        private set
    var lastHotJoinLoopSourceEndMs: Long? = null
        private set
    var hotJoinReturnLaneIndex: Int = 1
    var hotJoinCommitLifecycle: PlaybackLaneLifecycle = PlaybackLaneLifecycle.Active
    private val laneLifecycleOverrides = mutableMapOf<Int, PlaybackLaneLifecycle>()

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
        beginHotJoinCalls += 1
        lastHotJoinWavPath = wavFilePath
        lastHotJoinGain = gain
        lastHotJoinClipStartMs = timelineClipStartMs
        lastHotJoinClipDurationMs = timelineClipDurationMs
        lastHotJoinLoopEnabled = loopEnabled
        lastHotJoinLoopSourceStartMs = loopSourceStartMs
        lastHotJoinLoopSourceEndMs = loopSourceEndMs
        laneLifecycleOverrides[hotJoinReturnLaneIndex] = PlaybackLaneLifecycle.Preparing
        laneLifecycleOverrides[hotJoinReturnLaneIndex] = hotJoinCommitLifecycle
        return hotJoinReturnLaneIndex
    }

    override fun cancelHotJoinLane(laneIndex: Int) {
        laneLifecycleOverrides[laneIndex] = PlaybackLaneLifecycle.Cancelled
    }

    override fun playbackLaneLifecycle(laneIndex: Int): PlaybackLaneLifecycle =
        laneLifecycleOverrides[laneIndex] ?: PlaybackLaneLifecycle.Inactive

    override fun stopPlayback(): Boolean {
        stopPlaybackCalls += 1
        _playbackState.value = false
        transportPositionMsValue = 0L
        masterPeakHoldLinearValue = 0f
        return stopPlaybackResult
    }

    var releaseCalls = 0
        private set

    override fun release() {
        releaseCalls += 1
        _playbackState.value = false
        _recordingInputLevel.value = 0f
    }

    override fun recordingFirstSampleTransportFrame(): Long = CapturePort.RecordingFirstSampleTransportUnset

    override fun transportStartFrame(): Long = 0L

    override fun transportFrame(): Long = 0L

    override fun liveOutputLatencyNs(): Long = PlaybackTransportSync.LiveOutputLatencyUnsetNs

    override fun sessionPerceivedPlaybackOffsetMs(): Long =
        RecordingStopSnapshot.SessionPerceivedPlaybackOffsetUnset

    override fun readRecordingStopSnapshot(): RecordingStopSnapshot =
        RecordingStopSnapshot(
            firstSampleTransportPositionMs = recordingFirstSampleTransportPositionMs(),
            capturedFrameCount = recordingCapturedFrameCount(),
            capturedDurationMs = recordingCapturedDurationMs(),
            sessionPerceivedPlaybackOffsetMs = sessionPerceivedPlaybackOffsetMs(),
        )

    override fun captureLiveSessionLatencySnapshot() =
        LiveSessionLatencySnapshot.EMPTY

    override fun configureSessionTransportLatencies(
        inputLatencyMs: Double,
        outputLatencyMs: Double,
    ) = Unit

    override suspend fun renderOfflineMixdown(
        spec: MultiPlaybackSpec,
        outputPath: String,
        onProgress: (Float) -> Unit,
    ): MixdownResult = MixdownResult.Failed

    override fun cancelOfflineMixdown() = Unit
}
