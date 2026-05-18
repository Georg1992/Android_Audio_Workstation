package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun TimelinePlayheadScrubberWaveformArea(
    playheadFraction: Float,
    metrics: TimelinePlayheadWaveformMetrics,
    onPlayheadFractionPreview: (Float) -> Unit,
    onPlayheadFractionCommit: (Float) -> Unit,
    inputLocked: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.then(
            if (inputLocked) {
                Modifier
            } else {
                Modifier.pointerInput(metrics.waveformTimelineWidthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var fraction = metrics.fractionFromLocalXPx(down.position.x)
                        onPlayheadFractionPreview(fraction)
                        drag(down.id) { change ->
                            change.consume()
                            fraction = metrics.fractionFromLocalXPx(change.position.x)
                            onPlayheadFractionPreview(fraction)
                        }
                        onPlayheadFractionCommit(fraction)
                    }
                }
            }
        ),
    ) {
        TimelinePlayheadMarker(
            fraction = playheadFraction,
            showTopHandle = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
