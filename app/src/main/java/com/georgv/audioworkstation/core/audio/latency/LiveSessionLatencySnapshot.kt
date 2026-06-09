package com.georgv.audioworkstation.core.audio.latency

import com.georgv.audioworkstation.engine.AudioCallbackCostSnapshot
import com.georgv.audioworkstation.engine.AudioInputLoopCostSnapshot
import com.georgv.audioworkstation.engine.OboeStreamCapabilityProbe

/**
 * Captured while input/output streams are still open, immediately before recorder stop.
 */
data class LiveSessionLatencySnapshot(
    val outputProbe: OboeStreamCapabilityProbe?,
    val inputProbe: OboeStreamCapabilityProbe?,
    val outputCallbackCost: AudioCallbackCostSnapshot?,
    val inputLoopCost: AudioInputLoopCostSnapshot?,
) {
    companion object {
        val EMPTY =
            LiveSessionLatencySnapshot(
                outputProbe = null,
                inputProbe = null,
                outputCallbackCost = null,
                inputLoopCost = null,
            )
    }
}
