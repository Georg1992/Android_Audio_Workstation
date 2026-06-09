package com.georgv.audioworkstation.core.audio.capability

import android.util.Log
import java.util.Locale

internal object LatencyDeviceSummaryLog {
    private const val TAG = "AudioSyncDiag"

    fun log(summary: DeviceLatencySummary) {
        Log.i(
            TAG,
            "[LATENCY_DEVICE_SUMMARY] " +
                "routeKey=${summary.routeKey} " +
                "sampleRate=${summary.sampleRate} " +
                "profileState=${summary.profileState} " +
                "outputMedianMs=${formatMs(summary.outputMedianMs)} " +
                "outputP95Ms=${formatMs(summary.outputP95Ms)} " +
                "inputCaptureMedianMs=${formatMs(summary.inputCaptureMedianMs)} " +
                "roundTripMedianMs=${formatMs(summary.roundTripMedianMs)} " +
                "jitterMedianMs=${formatMs(summary.jitterMedianMs)} " +
                "appAddedOutputP95Ms=${formatMs(summary.appAddedOutputP95Ms)} " +
                "appAddedInputP95Ms=${formatMs(summary.appAddedInputP95Ms)} " +
                "lowLatencyOutputGranted=${summary.lowLatencyOutputGranted} " +
                "lowLatencyInputGranted=${summary.lowLatencyInputGranted} " +
                "bestKnownBackend=${summary.bestKnownBackend} " +
                "highLatencyRoute=${summary.highLatencyRoute} " +
                "dataConfidence=${summary.dataConfidence} " +
                "missingData=${summary.missingData.joinToString(",").ifEmpty { "none" }} " +
                "warnings=${summary.warnings.joinToString(";").ifEmpty { "none" }}",
        )
    }

    private fun formatMs(value: Double?): String =
        if (value != null && value.isFinite() && value >= 0.0) {
            String.format(Locale.US, "%.3f", value)
        } else {
            "n/a"
        }
}
