package com.georgv.audioworkstation.core.audio

import kotlinx.coroutines.flow.StateFlow

/** Read-only metering and transport clock for UI sync. */
interface MeterPort {
    val recordingInputLevel: StateFlow<Float>

    fun readMasterPeakHoldLinear(): Float

    /** Latest heard samples, [TransportWaveformTrace.PointCount] floats in -1..1. */
    fun readTransportWaveform(): FloatArray

    fun resetMasterPeakHold()

    fun transportPositionMs(): Long

    fun transportStartFrame(): Long

    fun transportFrame(): Long

    fun liveOutputLatencyNs(): Long

    /** False from each arm until the callback mixes source audio. The wall clock can run earlier. */
    fun outputHasRendered(): Boolean
}
