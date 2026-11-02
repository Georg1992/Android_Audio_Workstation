package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import com.georgv.audioworkstation.core.audio.latency.LiveSessionLatencySnapshot
import com.georgv.audioworkstation.core.audio.latency.latencyMsToNs
import com.georgv.audioworkstation.core.coroutines.checkNotMainThreadForNativeLifecycle
import com.georgv.audioworkstation.core.diagnostics.PlaybackStartupTrace
import com.georgv.audioworkstation.core.diagnostics.ThreadingDiagnostics
import com.georgv.audioworkstation.core.diagnostics.TransportFrameDiagnostics
import com.georgv.audioworkstation.engine.NativeEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeCapturePort @Inject constructor(
    private val nativeEngine: NativeEngine,
    private val monitors: NativeAudioMonitors,
    private val routeKeySource: AudioRouteKeySource,
    private val audioFilePathProvider: AudioFilePathProvider,
    private val sessionTransportGate: SessionTransportCapabilityGate,
    private val microphoneCaptureForeground: MicrophoneCaptureForeground,
) : CapturePort {
    override fun recordingFirstSampleTransportPositionMs(): Long {
        checkNotMainThreadForNativeLifecycle("recordingFirstSampleTransportPositionMs")
        return nativeEngine.recordingFirstSampleTransportPositionMs()
    }

    override fun recordingFirstSampleTransportFrame(): Long {
        checkNotMainThreadForNativeLifecycle("recordingFirstSampleTransportFrame")
        return nativeEngine.recordingFirstSampleTransportFrame()
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

    override fun startOverdubRecordingSession(
        playbackSpec: MultiPlaybackSpec,
        recordingSpec: RecordingSpec,
        outputPath: String,
    ): String? {
        checkNotMainThreadForNativeLifecycle("startOverdubRecordingSession")
        sessionTransportGate.ensurePreparedForSampleRate(playbackSpec.sampleRate)
        ThreadingDiagnostics.logWorkBoundary("NativeAudioController.startOverdubRecordingSession", phase = "beforeJni")
        PlaybackStartupTrace.logJniBoundary(path = "overdub_session", phase = "before_jni")
        microphoneCaptureForeground.start()
        val started =
            nativeEngine.startOverdubRecordingSession(
                playbackSpec = playbackSpec,
                recordingSpec = recordingSpec,
                outputPath = outputPath,
                inputRouteKey = routeKeySource.routeKey(playbackSpec.sampleRate),
            )
        PlaybackStartupTrace.logJniBoundary(path = "overdub_session", phase = "after_jni")
        ThreadingDiagnostics.logWorkBoundary("NativeAudioController.startOverdubRecordingSession", phase = "afterJni")
        if (!started) {
            microphoneCaptureForeground.stop()
            return null
        }
        TransportFrameDiagnostics.logOverdubSessionArm(playbackSpec)
        monitors.monitorPlaybackCompletion(waitForActiveFirst = true)
        monitors.monitorRecordingInputLevel()
        return outputPath
    }

    override fun startRecording(spec: RecordingSpec, outputPath: String?): String? {
        checkNotMainThreadForNativeLifecycle("startRecording")
        sessionTransportGate.ensurePreparedForSampleRate(spec.sampleRate)
        ThreadingDiagnostics.logWorkBoundary("NativeAudioController.startRecording", phase = "entered")
        val resolvedPath =
            outputPath ?: audioFilePathProvider.trackOutputPath(spec.projectId, spec.trackId)
                ?: return null
        val request = spec.toRecordingRequest(resolvedPath)
        ThreadingDiagnostics.logWorkBoundary("NativeAudioController.startRecording", phase = "beforeJni")
        microphoneCaptureForeground.start()
        val started = nativeEngine.startRecording(request)
        ThreadingDiagnostics.logWorkBoundary("NativeAudioController.startRecording", phase = "afterJni")
        if (!started) {
            microphoneCaptureForeground.stop()
            return null
        }
        monitors.monitorRecordingInputLevel()
        return resolvedPath
    }

    override fun isRecordingCaptureFailed(): Boolean {
        checkNotMainThreadForNativeLifecycle("isRecordingCaptureFailed")
        return nativeEngine.isRecordingCaptureFailed()
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

    override fun stopRecording(): RecordingStopKind {
        checkNotMainThreadForNativeLifecycle("stopRecording")
        monitors.cancelRecordingLevelMonitor()
        val kind = nativeEngine.stopRecording()
        monitors.markRecordingLevelSilent()
        return kind
    }
}
