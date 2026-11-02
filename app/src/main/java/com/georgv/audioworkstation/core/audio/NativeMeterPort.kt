package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.coroutines.checkNotMainThreadForNativeLifecycle
import com.georgv.audioworkstation.engine.NativeEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

@Singleton
class NativeMeterPort @Inject constructor(
    private val nativeEngine: NativeEngine,
    private val monitors: NativeAudioMonitors,
) : MeterPort {
    override val recordingInputLevel: StateFlow<Float> = monitors.recordingInputLevel

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

    override fun transportStartFrame(): Long {
        checkNotMainThreadForNativeLifecycle("transportStartFrame")
        return nativeEngine.transportStartFrame()
    }

    override fun transportFrame(): Long {
        checkNotMainThreadForNativeLifecycle("transportFrame")
        return nativeEngine.transportFrame()
    }

    override fun liveOutputLatencyNs(): Long {
        checkNotMainThreadForNativeLifecycle("liveOutputLatencyNs")
        return nativeEngine.liveOutputLatencyNs()
    }
}
