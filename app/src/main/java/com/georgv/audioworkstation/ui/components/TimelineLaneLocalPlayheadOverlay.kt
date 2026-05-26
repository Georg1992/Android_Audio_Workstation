package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.zIndex
import com.georgv.audioworkstation.ui.theme.AppColors

@Composable
internal fun TimelineLaneLocalPlayheadOverlay(
    sourcePlayheadMs: Long,
    sourceDurationMs: Long,
    playheadLineWidthPx: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier =
            modifier
                .fillMaxSize()
                .zIndex(2f),
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val x =
            sourceMsToXInLaneClip(
                sourceMs = sourcePlayheadMs,
                clipWidthPx = size.width,
                sourceDurationMs = sourceDurationMs,
            )
        drawLine(
            color = AppColors.Red,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = playheadLineWidthPx,
        )
    }
}
