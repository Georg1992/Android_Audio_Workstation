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
    metrics: TimelinePlayheadWaveformMetrics,
    onPlayheadScrubStarted: () -> Unit = {},
    onPlayheadScrubCancelled: () -> Unit = {},
    onPlayheadPositionPreview: (Long) -> Unit,
    onPlayheadPositionCommit: (Long) -> Unit,
    inputLocked: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.then(
            if (inputLocked) {
                Modifier
            } else {
                Modifier.pointerInput(
                    metrics.waveformTimelineWidthPx,
                    metrics.timelineDurationMs,
                ) {
                    awaitEachGesture {
                        var committed = false
                        try {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            onPlayheadScrubStarted()
                            var positionMs = metrics.positionMsFromLocalXPx(down.position.x)
                            onPlayheadPositionPreview(positionMs)
                            drag(down.id) { change ->
                                change.consume()
                                positionMs = metrics.positionMsFromLocalXPx(change.position.x)
                                onPlayheadPositionPreview(positionMs)
                            }
                            onPlayheadPositionCommit(positionMs)
                            committed = true
                        } finally {
                            if (!committed) {
                                onPlayheadScrubCancelled()
                            }
                        }
                    }
                }
            }
        ),
    )
}
