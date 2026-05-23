package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlin.math.roundToLong

/**
 * Single source of truth for mapping timeline time to the shared waveform-column x axis.
 * Top ruler and every track lane must use the same [timelineDurationMs], [contentWidthPx], and
 * [contentStartPx] when drawing or hit-testing the playhead.
 */
fun timelineMsToX(
    timeMs: Long,
    timelineDurationMs: Long,
    contentWidthPx: Float,
    contentStartPx: Float = 0f,
): Float {
    if (timelineDurationMs <= 0L || contentWidthPx <= 0f) return contentStartPx
    val fraction = (timeMs.toFloat() / timelineDurationMs.toFloat()).coerceIn(0f, 1f)
    return contentStartPx + fraction * contentWidthPx
}

fun timelineXToMs(
    xPx: Float,
    timelineDurationMs: Long,
    contentWidthPx: Float,
    contentStartPx: Float = 0f,
): Long {
    if (timelineDurationMs <= 0L || contentWidthPx <= 0f) return 0L
    val localX = (xPx - contentStartPx).coerceIn(0f, contentWidthPx)
    val fraction = localX / contentWidthPx
    return (fraction * timelineDurationMs.toFloat()).roundToLong()
}

fun timelinePlayheadFraction(positionMs: Long, timelineDurationMs: Long): Float {
    if (timelineDurationMs <= 0L) return 0f
    return (positionMs.toFloat() / timelineDurationMs.toFloat()).coerceIn(0f, 1f)
}

fun timelinePlayheadPositionMs(fraction: Float, timelineDurationMs: Long): Long {
    if (timelineDurationMs <= 0L) return 0L
    return (fraction.coerceIn(0f, 1f) * timelineDurationMs.toFloat()).roundToLong()
}

fun timelinePlayheadClampedPositionMs(positionMs: Long, timelineDurationMs: Long): Long =
    timelinePlayheadPositionMs(
        timelinePlayheadFraction(positionMs, timelineDurationMs),
        timelineDurationMs,
    )

fun timelinePlayheadFractionFromWaveformX(
    xPx: Float,
    waveformTimelineWidthPx: Float,
    contentStartPx: Float = 0f,
): Float {
    if (waveformTimelineWidthPx <= 0f) return 0f
    val localX = (xPx - contentStartPx).coerceIn(0f, waveformTimelineWidthPx)
    return (localX / waveformTimelineWidthPx).coerceIn(0f, 1f)
}

data class TimelinePlayheadWaveformMetrics(
    val waveformTimelineWidthPx: Float,
    val timelineDurationMs: Long,
    val contentStartPx: Float = 0f,
) {
    fun positionMsFromLocalXPx(localXPx: Float): Long =
        timelineXToMs(localXPx, timelineDurationMs, waveformTimelineWidthPx, contentStartPx)
}

@Composable
fun rememberTimelinePlayheadWaveformMetrics(
    waveformAreaWidth: Dp,
    timelineDurationMs: Long,
    contentStartPx: Float = 0f,
): TimelinePlayheadWaveformMetrics {
    val widthPx = with(LocalDensity.current) { waveformAreaWidth.toPx() }
    return remember(widthPx, timelineDurationMs, contentStartPx) {
        TimelinePlayheadWaveformMetrics(
            waveformTimelineWidthPx = widthPx,
            timelineDurationMs = timelineDurationMs,
            contentStartPx = contentStartPx,
        )
    }
}

/**
 * Mirrors [TrackCard] horizontal chrome: timeline column, gap, fader — so the scrubber and
 * per-track playheads share the same waveform timeline width as the track list.
 */
@Composable
fun TimelinePlayheadTrackRowSlot(
    timelineDurationMs: Long,
    modifier: Modifier = Modifier,
    waveformContent: @Composable BoxScope.(TimelinePlayheadWaveformMetrics) -> Unit,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                BoxWithConstraintsPlayheadWaveformSlot(
                    timelineDurationMs = timelineDurationMs,
                    waveformContent = waveformContent,
                )
                Spacer(
                    modifier = Modifier
                        .weight(TimelineMetadataWidthFraction)
                        .fillMaxHeight(),
                )
            }
        }
        Spacer(Modifier.width(Dimens.Gap))
        Spacer(Modifier.width(Dimens.FaderWidth))
    }
}

@Composable
private fun RowScope.BoxWithConstraintsPlayheadWaveformSlot(
    timelineDurationMs: Long,
    waveformContent: @Composable BoxScope.(TimelinePlayheadWaveformMetrics) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .weight(TimelineWaveformWidthFraction)
            .fillMaxHeight(),
    ) {
        val metrics =
            rememberTimelinePlayheadWaveformMetrics(
                waveformAreaWidth = maxWidth,
                timelineDurationMs = timelineDurationMs,
            )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .reportScrubberWaveformBounds(),
        ) {
            waveformContent(metrics)
        }
    }
}

/**
 * Shared playhead line for the top scrubber ruler and per-track timeline lanes.
 */
@Composable
fun TimelinePlayheadMarker(
    playheadPositionMs: Long,
    timelineDurationMs: Long,
    modifier: Modifier = Modifier,
    contentStartPx: Float = 0f,
    showTopHandle: Boolean = false,
    lineWidth: Dp = 1.dp,
    handleWidth: Dp = 10.dp,
    handleHeight: Dp = 8.dp,
) {
    val lineColor = AppColors.Red
    val density = LocalDensity.current
    val lineWidthPx = with(density) { lineWidth.toPx() }
    val handleWidthPx = with(density) { handleWidth.toPx() }
    val handleHeightPx = with(density) { handleHeight.toPx() }

    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val x =
            timelineMsToX(
                timeMs = playheadPositionMs,
                timelineDurationMs = timelineDurationMs,
                contentWidthPx = size.width,
                contentStartPx = contentStartPx,
            )

        if (showTopHandle) {
            val handleApexY = handleHeightPx
            val path =
                Path().apply {
                    moveTo(x - handleWidthPx / 2f, 0f)
                    lineTo(x + handleWidthPx / 2f, 0f)
                    lineTo(x, handleApexY)
                    close()
                }
            drawPath(path, color = lineColor)
            drawLine(
                color = lineColor,
                start = Offset(x, handleApexY),
                end = Offset(x, size.height),
                strokeWidth = lineWidthPx,
            )
        } else {
            drawLine(
                color = lineColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = lineWidthPx,
            )
        }
    }
}
