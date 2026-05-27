package com.georgv.audioworkstation.ui.components

import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Temporary layout diagnostics for scrubber vs lane waveform coordinate space. */
object TimelineGeometryDebug {
    const val TAG = "TimelineGeometry"

    var loggingEnabled: Boolean = false
}

fun Modifier.reportScrubberWaveformBounds(): Modifier =
    reportTimelineWaveformBounds("scrubber")

fun Modifier.reportLaneWaveformBounds(): Modifier =
    reportTimelineWaveformBounds("lane")

private fun Modifier.reportTimelineWaveformBounds(label: String): Modifier =
    onGloballyPositioned { coordinates ->
        if (!TimelineGeometryDebug.loggingEnabled) return@onGloballyPositioned
        val bounds = coordinates.boundsInRoot()
        Log.d(
            TimelineGeometryDebug.TAG,
            "$label waveform area globalX=${bounds.left} width=${bounds.width}",
        )
    }
