package com.georgv.audioworkstation.ui.components

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Temporary layout diagnostics for scrubber vs lane waveform coordinate space. */
object TimelineGeometryDebug {
    const val TAG = "TimelineGeometry"

    var loggingEnabled: Boolean = false

    val scrubberWaveformBounds = mutableStateOf<TimelineWaveformBoundsReport?>(null)
    val laneWaveformBounds = mutableStateOf<TimelineWaveformBoundsReport?>(null)

    fun reset() {
        scrubberWaveformBounds.value = null
        laneWaveformBounds.value = null
    }
}

data class TimelineWaveformBoundsReport(
    val label: String,
    val globalXPx: Float,
    val widthPx: Float,
)

fun Modifier.reportScrubberWaveformBounds(): Modifier =
    reportTimelineWaveformBounds("scrubber") {
        TimelineGeometryDebug.scrubberWaveformBounds.value = it
    }

fun Modifier.reportLaneWaveformBounds(): Modifier =
    reportTimelineWaveformBounds("lane") {
        TimelineGeometryDebug.laneWaveformBounds.value = it
    }

private fun Modifier.reportTimelineWaveformBounds(
    label: String,
    store: (TimelineWaveformBoundsReport) -> Unit,
): Modifier =
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInRoot()
        val report =
            TimelineWaveformBoundsReport(
                label = label,
                globalXPx = bounds.left,
                widthPx = bounds.width,
            )
        store(report)
        if (TimelineGeometryDebug.loggingEnabled) {
            Log.d(
                TimelineGeometryDebug.TAG,
                "$label waveform area globalX=${report.globalXPx} width=${report.widthPx}",
            )
        }
    }
