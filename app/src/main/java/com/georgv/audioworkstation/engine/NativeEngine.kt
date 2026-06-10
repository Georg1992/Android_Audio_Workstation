package com.georgv.audioworkstation.engine

import com.georgv.audioworkstation.ui.diagnostics.AudioSyncLogConfig
import com.georgv.audioworkstation.core.audio.MultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.PanRange
import com.georgv.audioworkstation.core.audio.PlaybackLaneLifecycle
import com.georgv.audioworkstation.core.audio.RecordingRequest
import com.georgv.audioworkstation.core.audio.RecordingSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeEngine @Inject constructor() {

    init {
        syncLogConfig()
    }

    @Volatile
    private var offlineMixdownProgressCallback: ((Float) -> Unit)? = null

    fun startRecording(request: RecordingRequest): Boolean {
        syncLogConfig()
        return nativeStartRecording(
            sampleRate = request.sampleRate,
            fileBitDepth = request.fileBitDepth,
            channelMode = request.channelMode.ordinal,
            outputPath = request.outputPath,
            startPositionMs = request.timelineStartOffsetMs,
        )
    }

    fun stopRecording(): Boolean = nativeStopRecording()

    fun inputStreamSnapshot(): OboeStreamSnapshot? =
        parseOboeStreamSnapshot(nativeGetInputStreamSnapshot())

    fun outputStreamSnapshot(): OboeStreamSnapshot? =
        parseOboeStreamSnapshot(nativeGetOutputStreamSnapshot())

    fun probeOutputStreamCapability(): OboeStreamCapabilityProbe? =
        OboeStreamCapabilityProbe.fromNativeValues(nativeProbeOutputStreamCapability())

    fun probeInputStreamCapability(): OboeStreamCapabilityProbe? =
        OboeStreamCapabilityProbe.fromNativeValues(nativeProbeInputStreamCapability())

    fun softwareBufferProfile(): SoftwareBufferProfile? =
        SoftwareBufferProfile.fromNativeValues(nativeGetSoftwareBufferProfile())

    fun outputCallbackCostSnapshot(): AudioCallbackCostSnapshot? =
        AudioCallbackCostSnapshot.fromNativeValues(nativeGetOutputCallbackCostSnapshot())

    fun inputLoopCostSnapshot(): AudioInputLoopCostSnapshot? =
        AudioInputLoopCostSnapshot.fromNativeValues(nativeGetInputLoopCostSnapshot())

    fun playbackSessionTimings(): PlaybackSessionTimings? = parsePlaybackSessionTimings(nativeGetPlaybackSessionTimings())

    private fun parsePlaybackSessionTimings(values: LongArray?): PlaybackSessionTimings? {
        if (values == null || values.size < 12) return null
        return PlaybackSessionTimings(
            playbackArmSteadyNs = values[0],
            firstInputSampleSteadyNs = values[1],
            firstNonSilentOutputSteadyNs = values[2],
            firstAudibleOutputSteadyNs = values[3],
            prerollFrames = values[4].toInt(),
            ioBatchFrames = values[5].toInt(),
            recordReadFrames = values[6].toInt(),
            playbackArmTransportStartFrame = values[7],
            firstNonSilentTransportFrame = values[8],
            firstAudiblePeakTransportFrame = values[9],
            firstAudiblePeakMicro = values[10],
            openInputBeginSteadyNs = values.getOrElse(11) { 0L },
            openInputDoneSteadyNs = values.getOrElse(12) { 0L },
            oboeStreamOpenBeginSteadyNs = values.getOrElse(13) { 0L },
            oboeStreamOpenDoneSteadyNs = values.getOrElse(14) { 0L },
            oboeStreamStartDoneSteadyNs = values.getOrElse(15) { 0L },
            firstOboeCallbackSteadyNs =
                values.getOrElse(PlaybackTimingsFirstOboeCallbackIndex) { 0L },
        )
    }

    private fun parseOboeStreamSnapshot(values: LongArray?): OboeStreamSnapshot? {
        if (values == null || values.size < 8) return null
        return OboeStreamSnapshot(
            sampleRateHz = values[0].toInt(),
            channelCount = values[1].toInt(),
            framesPerBurst = values[2].toInt(),
            bufferCapacityInFrames = values[3].toInt(),
            bufferSizeInFrames = values[4].toInt(),
            performanceMode = values[5].toInt(),
            sharingMode = values[6].toInt(),
            audioSessionId = values[7].toInt(),
        )
    }

    fun recordingFirstSampleTransportPositionMs(): Long =
        nativeGetRecordingFirstSampleTransportPositionMs()

    fun recordingFirstSampleTransportFrame(): Long =
        nativeGetRecordingFirstSampleTransportFrame()

    fun recordingCapturedFrameCount(): Long = nativeGetRecordingCapturedFrameCount()

    fun recordingCapturedDurationMs(): Long = nativeGetRecordingCapturedDurationMs()

    fun sessionPerceivedPlaybackOffsetMs(): Long = nativeGetSessionPerceivedPlaybackOffsetMs()

    /** Live HAL output latency (ns), or [PlaybackTransportSync.LiveOutputLatencyUnsetNs] when invalid. */
    fun liveOutputLatencyNs(): Long = nativeGetLiveOutputLatencyNs()

    fun lastPlacementClockDeltaMs(): Long = nativeGetLastPlacementClockDeltaMs()

    fun startOverdubRecordingSession(
        playbackSpec: MultiPlaybackSpec,
        recordingSpec: RecordingSpec,
        outputPath: String,
        inputRouteKey: String,
    ): Boolean {
        syncLogConfig()
        return nativeStartOverdubRecordingSession(
            sampleRate = playbackSpec.sampleRate,
            wavPaths = playbackSpec.lanes.map { it.wavFilePath }.toTypedArray(),
            gains = playbackSpec.lanes.map { it.gain }.toFloatArray(),
            startPositionMs = playbackSpec.startPositionMs,
            sessionTimelineEndMs = playbackSpec.sessionTimelineEndMs,
            laneClipStartMs = playbackSpec.lanes.map { it.timelineClipStartMs }.toLongArray(),
            laneClipDurationMs = playbackSpec.lanes.map { it.timelineClipDurationMs }.toLongArray(),
            laneLoopEnabled = playbackSpec.lanes.map { it.loopEnabled }.toBooleanArray(),
            laneLoopSourceStartMs = playbackSpec.lanes.map { it.loopSourceStartMs }.toLongArray(),
            laneLoopSourceEndMs = playbackSpec.lanes.map { it.loopSourceEndMs }.toLongArray(),
            laneSourceTrimStartMs = playbackSpec.lanes.map { it.sourceTrimStartMs }.toLongArray(),
            lanePan = playbackSpec.lanes.map { it.pan }.toFloatArray(),
            channelMode = recordingSpec.channelMode.ordinal,
            routeKey = inputRouteKey,
            outputPath = outputPath,
        )
    }

    fun setSessionTransportLatenciesNs(inputLatencyNs: Long, outputLatencyNs: Long) {
        nativeSetSessionTransportLatenciesNs(inputLatencyNs, outputLatencyNs)
    }

    fun recordingInputLevel(): Float = nativeGetRecordingInputLevel().coerceIn(0f, 1f)

    fun masterPeakHoldLinear(): Float = nativeGetMasterPeakHoldLinear().coerceAtLeast(0f)

    fun resetMasterPeakHold() {
        nativeResetMasterPeakHold()
    }

    fun startMultiPlayback(spec: MultiPlaybackSpec): Boolean {
        syncLogConfig()
        return nativeStartMultiPlayback(
            sampleRate = spec.sampleRate,
            wavPaths = spec.lanes.map { it.wavFilePath }.toTypedArray(),
            gains = spec.lanes.map { it.gain }.toFloatArray(),
            startPositionMs = spec.startPositionMs,
            sessionTimelineEndMs = spec.sessionTimelineEndMs,
            laneClipStartMs = spec.lanes.map { it.timelineClipStartMs }.toLongArray(),
            laneClipDurationMs = spec.lanes.map { it.timelineClipDurationMs }.toLongArray(),
            laneLoopEnabled = spec.lanes.map { it.loopEnabled }.toBooleanArray(),
            laneLoopSourceStartMs = spec.lanes.map { it.loopSourceStartMs }.toLongArray(),
            laneLoopSourceEndMs = spec.lanes.map { it.loopSourceEndMs }.toLongArray(),
            laneSourceTrimStartMs = spec.lanes.map { it.sourceTrimStartMs }.toLongArray(),
            lanePan = spec.lanes.map { it.pan }.toFloatArray(),
        )
    }

    fun rearmOverdubPlaybackDuringRecording(spec: MultiPlaybackSpec): Boolean {
        syncLogConfig()
        return nativeRearmOverdubPlaybackDuringRecording(
            sampleRate = spec.sampleRate,
            wavPaths = spec.lanes.map { it.wavFilePath }.toTypedArray(),
            gains = spec.lanes.map { it.gain }.toFloatArray(),
            startPositionMs = spec.startPositionMs,
            sessionTimelineEndMs = spec.sessionTimelineEndMs,
            laneClipStartMs = spec.lanes.map { it.timelineClipStartMs }.toLongArray(),
            laneClipDurationMs = spec.lanes.map { it.timelineClipDurationMs }.toLongArray(),
            laneLoopEnabled = spec.lanes.map { it.loopEnabled }.toBooleanArray(),
            laneLoopSourceStartMs = spec.lanes.map { it.loopSourceStartMs }.toLongArray(),
            laneLoopSourceEndMs = spec.lanes.map { it.loopSourceEndMs }.toLongArray(),
            laneSourceTrimStartMs = spec.lanes.map { it.sourceTrimStartMs }.toLongArray(),
            lanePan = spec.lanes.map { it.pan }.toFloatArray(),
        )
    }

    fun setPlaybackLaneGain(laneIndex: Int, gain: Float) {
        nativeSetPlaybackLaneGain(laneIndex, gain.coerceIn(0f, 1f))
    }

    fun setPlaybackLanePan(laneIndex: Int, pan: Float) {
        nativeSetPlaybackLanePan(laneIndex, PanRange.clamp(pan))
    }

    fun setPlaybackLaneAudible(laneIndex: Int, audible: Boolean) {
        nativeSetPlaybackLaneAudible(laneIndex, audible)
    }

    fun beginHotJoinLane(
        wavFilePath: String,
        gain: Float,
        timelineClipStartMs: Long = 0L,
        timelineClipDurationMs: Long = 0L,
        loopEnabled: Boolean = false,
        loopSourceStartMs: Long = 0L,
        loopSourceEndMs: Long = 0L,
        sourceTrimStartMs: Long = 0L,
        pan: Float = 0f,
    ): Int =
        nativeBeginHotJoinLane(
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

    fun cancelHotJoinLane(laneIndex: Int) {
        nativeCancelHotJoinLane(laneIndex)
    }

    fun playbackLaneLifecycle(laneIndex: Int): PlaybackLaneLifecycle =
        PlaybackLaneLifecycle.entries[nativeGetPlaybackLaneLifecycle(laneIndex)]

    fun isPlaybackActive(): Boolean = nativeIsPlaybackActive()

    /**
     * Sample-domain transport timeline frame (Clock.2).
     * Advanced by playback render while active; does not drive UI playhead until Clock.4.
     */
    fun transportFrame(): Long = nativeGetTransportFrame()

    /** Transport origin frame from last playback arm. */
    fun transportStartFrame(): Long = nativeGetTransportStartFrame()

    /** [transportFrame] as milliseconds at project sample rate (same integer math as native). */
    fun transportPositionMs(): Long = nativeGetTransportPositionMs()

    fun stopPlayback(): Boolean = nativeStopPlayback()

    fun renderOfflineMixdown(
        spec: MultiPlaybackSpec,
        outputPath: String,
        onProgress: (Float) -> Unit,
    ): NativeMixdownStatus {
        offlineMixdownProgressCallback = onProgress
        return try {
            val statusCode =
                nativeRenderOfflineMixdown(
                    sampleRate = spec.sampleRate,
                    wavPaths = spec.lanes.map { it.wavFilePath }.toTypedArray(),
                    gains = spec.lanes.map { it.gain }.toFloatArray(),
                    startPositionMs = spec.startPositionMs,
                    sessionTimelineEndMs = spec.sessionTimelineEndMs,
                    laneClipStartMs = spec.lanes.map { it.timelineClipStartMs }.toLongArray(),
                    laneClipDurationMs = spec.lanes.map { it.timelineClipDurationMs }.toLongArray(),
                    laneLoopEnabled = spec.lanes.map { it.loopEnabled }.toBooleanArray(),
                    laneLoopSourceStartMs = spec.lanes.map { it.loopSourceStartMs }.toLongArray(),
                    laneLoopSourceEndMs = spec.lanes.map { it.loopSourceEndMs }.toLongArray(),
                    laneSourceTrimStartMs = spec.lanes.map { it.sourceTrimStartMs }.toLongArray(),
                    lanePan = spec.lanes.map { it.pan }.toFloatArray(),
                    outputPath = outputPath,
                )
            NativeMixdownStatus.fromCode(statusCode)
        } finally {
            offlineMixdownProgressCallback = null
        }
    }

    fun cancelOfflineMixdown() {
        nativeCancelOfflineMixdown()
    }

    /** Called from native mixdown progress on the audio IO thread. */
    fun dispatchOfflineMixdownProgress(progress: Float) {
        offlineMixdownProgressCallback?.invoke(progress.coerceIn(0f, 1f))
    }

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
        outputPath: String,
        startPositionMs: Long,
    ): Boolean

    private external fun nativeStopRecording(): Boolean

    private external fun nativeGetInputStreamSnapshot(): LongArray?

    private external fun nativeGetOutputStreamSnapshot(): LongArray?

    private external fun nativeGetPlaybackSessionTimings(): LongArray?

    private external fun nativeProbeOutputStreamCapability(): LongArray?

    private external fun nativeProbeInputStreamCapability(): LongArray?

    private external fun nativeGetSoftwareBufferProfile(): LongArray?

    private external fun nativeGetOutputCallbackCostSnapshot(): LongArray?

    private external fun nativeGetInputLoopCostSnapshot(): LongArray?

    internal fun syncLogConfig() {
        nativeSyncAudioSyncLogConfig(
            AudioSyncLogConfig.clockValidationMode,
            AudioSyncLogConfig.detailedStartupLogsEnabled,
            AudioSyncLogConfig.rawTimestampSpamEnabled,
            AudioSyncLogConfig.transportFrameVerboseEnabled,
        )
    }

    private external fun nativeSyncAudioSyncLogConfig(
        clockValidationMode: Boolean,
        detailedStartupLogsEnabled: Boolean,
        rawTimestampSpamEnabled: Boolean,
        transportFrameVerboseEnabled: Boolean,
    )

    private external fun nativeSetSessionTransportLatenciesNs(
        inputLatencyNs: Long,
        outputLatencyNs: Long,
    )

    private external fun nativeGetRecordingFirstSampleTransportPositionMs(): Long

    private external fun nativeGetRecordingFirstSampleTransportFrame(): Long

    private external fun nativeGetRecordingCapturedFrameCount(): Long

    private external fun nativeGetRecordingCapturedDurationMs(): Long

    private external fun nativeGetSessionPerceivedPlaybackOffsetMs(): Long

    private external fun nativeGetLiveOutputLatencyNs(): Long

    private external fun nativeGetLastPlacementClockDeltaMs(): Long

    private external fun nativeStartOverdubRecordingSession(
        sampleRate: Int,
        wavPaths: Array<String>,
        gains: FloatArray,
        startPositionMs: Long,
        sessionTimelineEndMs: Long,
        laneClipStartMs: LongArray,
        laneClipDurationMs: LongArray,
        laneLoopEnabled: BooleanArray,
        laneLoopSourceStartMs: LongArray,
        laneLoopSourceEndMs: LongArray,
        laneSourceTrimStartMs: LongArray,
        lanePan: FloatArray,
        channelMode: Int,
        routeKey: String,
        outputPath: String,
    ): Boolean

    private external fun nativeGetRecordingInputLevel(): Float

    private external fun nativeGetMasterPeakHoldLinear(): Float

    private external fun nativeResetMasterPeakHold()

    private external fun nativeStartMultiPlayback(
        sampleRate: Int,
        wavPaths: Array<String>,
        gains: FloatArray,
        startPositionMs: Long,
        sessionTimelineEndMs: Long,
        laneClipStartMs: LongArray,
        laneClipDurationMs: LongArray,
        laneLoopEnabled: BooleanArray,
        laneLoopSourceStartMs: LongArray,
        laneLoopSourceEndMs: LongArray,
        laneSourceTrimStartMs: LongArray,
        lanePan: FloatArray,
    ): Boolean

    private external fun nativeRearmOverdubPlaybackDuringRecording(
        sampleRate: Int,
        wavPaths: Array<String>,
        gains: FloatArray,
        startPositionMs: Long,
        sessionTimelineEndMs: Long,
        laneClipStartMs: LongArray,
        laneClipDurationMs: LongArray,
        laneLoopEnabled: BooleanArray,
        laneLoopSourceStartMs: LongArray,
        laneLoopSourceEndMs: LongArray,
        laneSourceTrimStartMs: LongArray,
        lanePan: FloatArray,
    ): Boolean

    private external fun nativeSetPlaybackLaneGain(laneIndex: Int, gain: Float)

    private external fun nativeSetPlaybackLanePan(laneIndex: Int, pan: Float)

    private external fun nativeSetPlaybackLaneAudible(laneIndex: Int, audible: Boolean)

    private external fun nativeBeginHotJoinLane(
        wavFilePath: String,
        gain: Float,
        timelineClipStartMs: Long,
        timelineClipDurationMs: Long,
        loopEnabled: Boolean,
        loopSourceStartMs: Long,
        loopSourceEndMs: Long,
        sourceTrimStartMs: Long,
        pan: Float,
    ): Int

    private external fun nativeCancelHotJoinLane(laneIndex: Int)

    private external fun nativeGetPlaybackLaneLifecycle(laneIndex: Int): Int

    private external fun nativeIsPlaybackActive(): Boolean

    private external fun nativeGetTransportFrame(): Long

    private external fun nativeGetTransportStartFrame(): Long

    private external fun nativeGetTransportPositionMs(): Long

    private external fun nativeStopPlayback(): Boolean

    private external fun nativeRenderOfflineMixdown(
        sampleRate: Int,
        wavPaths: Array<String>,
        gains: FloatArray,
        startPositionMs: Long,
        sessionTimelineEndMs: Long,
        laneClipStartMs: LongArray,
        laneClipDurationMs: LongArray,
        laneLoopEnabled: BooleanArray,
        laneLoopSourceStartMs: LongArray,
        laneLoopSourceEndMs: LongArray,
        laneSourceTrimStartMs: LongArray,
        lanePan: FloatArray,
        outputPath: String,
    ): Int

    private external fun nativeCancelOfflineMixdown()

    private external fun nativeReleaseEngine()

    private companion object {
        private const val PlaybackTimingsFirstOboeCallbackIndex = 16

        init {
            System.loadLibrary("audioworkstation")
        }
    }
}
