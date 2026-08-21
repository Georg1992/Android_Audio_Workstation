package com.georgv.audioworkstation.core.audio

import kotlinx.coroutines.flow.StateFlow

/** Read-only metering and transport clock for UI sync. */
interface MeterPort {
    val recordingInputLevel: StateFlow<Float>

    fun readMasterPeakHoldLinear(): Float

    fun resetMasterPeakHold()

    fun transportPositionMs(): Long

    fun transportStartFrame(): Long

    fun transportFrame(): Long

    fun liveOutputLatencyNs(): Long
}
