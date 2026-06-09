package com.georgv.audioworkstation.core.audio.latency

import com.georgv.audioworkstation.engine.OboeStreamCapabilityProbe

internal object OboeCapabilityLabels {
    const val PERFORMANCE_MODE_REQUESTED = "LowLatency"
    const val SHARING_MODE_REQUESTED = "Shared"

    fun audioApiLabel(api: Int): String =
        when (api) {
            0 -> "Unspecified"
            1 -> "OpenSLES"
            2 -> "AAudio"
            else -> "Other($api)"
        }

    fun performanceModeLabel(mode: Int): String =
        when (mode) {
            1 -> "PowerSaving"
            2 -> "LowLatency"
            0 -> "None"
            else -> "Other($mode)"
        }

    fun sharingModeLabel(mode: Int): String =
        when (mode) {
            0 -> "Shared"
            1 -> "Exclusive"
            else -> "Other($mode)"
        }

    fun formatLabel(format: Int): String =
        when (format) {
            1 -> "I16"
            2 -> "Float"
            3 -> "I24"
            4 -> "I32"
            else -> "Other($format)"
        }

    fun lowLatencyApplied(probe: OboeStreamCapabilityProbe): Boolean =
        probe.performanceModeActual == 2

    fun exclusiveApplied(probe: OboeStreamCapabilityProbe): Boolean =
        probe.sharingModeActual == 1
}
