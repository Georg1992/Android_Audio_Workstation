package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import com.georgv.audioworkstation.core.coroutines.checkNotMainThreadForNativeLifecycle
import com.georgv.audioworkstation.core.diagnostics.PlaybackStartupTrace
import com.georgv.audioworkstation.core.diagnostics.TransportFrameDiagnostics
import com.georgv.audioworkstation.engine.NativeEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

@Singleton
class NativePlaybackPort @Inject constructor(
    private val nativeEngine: NativeEngine,
    private val monitors: NativeAudioMonitors,
    private val sessionTransportGate: SessionTransportCapabilityGate,
) : PlaybackPort {
    override val playbackState: StateFlow<Boolean> = monitors.playbackState

    override fun isPlaybackEngineRunning(): Boolean {
        checkNotMainThreadForNativeLifecycle("isPlaybackEngineRunning")
        return nativeEngine.isPlaybackActive()
    }

    override fun startPlayback(spec: MultiPlaybackSpec): Boolean {
        checkNotMainThreadForNativeLifecycle("startPlayback")
        sessionTransportGate.ensurePreparedForSampleRate(spec.sampleRate)
        PlaybackStartupTrace.logJniBoundary(path = "multi_playback", phase = "before_jni")
        val started = nativeEngine.startMultiPlayback(spec)
        PlaybackStartupTrace.logJniBoundary(path = "multi_playback", phase = "after_jni")
        if (started) {
            TransportFrameDiagnostics.logPlaybackArm(
                spec = spec,
                transportStartFrame = nativeEngine.transportStartFrame(),
                transportFrame = nativeEngine.transportFrame(),
            )
            monitors.monitorPlaybackCompletion()
        }
        return started
    }

    override fun rearmOverdubPlaybackDuringRecording(spec: MultiPlaybackSpec): Boolean {
        checkNotMainThreadForNativeLifecycle("rearmOverdubPlaybackDuringRecording")
        sessionTransportGate.ensurePreparedForSampleRate(spec.sampleRate)
        PlaybackStartupTrace.logJniBoundary(path = "rearm_overdub", phase = "before_jni")
        val started = nativeEngine.rearmOverdubPlaybackDuringRecording(spec)
        PlaybackStartupTrace.logJniBoundary(path = "rearm_overdub", phase = "after_jni")
        if (started) {
            TransportFrameDiagnostics.logPlaybackArm(
                spec = spec,
                transportStartFrame = nativeEngine.transportStartFrame(),
                transportFrame = nativeEngine.transportFrame(),
            )
            monitors.monitorPlaybackCompletion()
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
        monitors.cancelPlaybackMonitor()
        val ok = nativeEngine.stopPlayback()
        monitors.markPlaybackStopped()
        return ok
    }

    override fun release() {
        checkNotMainThreadForNativeLifecycle("release")
        monitors.releaseMonitors()
        nativeEngine.releaseEngine()
    }
}
