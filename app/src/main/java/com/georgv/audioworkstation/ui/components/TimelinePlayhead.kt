package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

fun timelinePlayheadFraction(positionMs: Long, baseDurationMs: Long): Float {
    if (baseDurationMs <= 0L) return 0f
    return (positionMs.toFloat() / baseDurationMs.toFloat()).coerceIn(0f, 1f)
}

fun timelinePlayheadPositionMs(fraction: Float, baseDurationMs: Long): Long {
    if (baseDurationMs <= 0L) return 0L
    return (fraction.coerceIn(0f, 1f) * baseDurationMs.toFloat()).roundToLong()
}

fun timelinePlayheadClampedPositionMs(positionMs: Long, baseDurationMs: Long): Long =
    timelinePlayheadPositionMs(
        timelinePlayheadFraction(positionMs, baseDurationMs),
        baseDurationMs,
    )

fun timelinePlayheadFractionFromWaveformX(
    xPx: Float,
    waveformTimelineWidthPx: Float,
): Float {
    if (waveformTimelineWidthPx <= 0f) return 0f
    return (xPx / waveformTimelineWidthPx).coerceIn(0f, 1f)
}

fun timelinePlayheadXInWaveformArea(
    fraction: Float,
    waveformTimelineWidthPx: Float,
): Float = fraction.coerceIn(0f, 1f) * waveformTimelineWidthPx

data class TimelinePlayheadWaveformMetrics(
    val waveformTimelineWidthPx: Float,
) {
    fun fractionFromLocalXPx(localXPx: Float): Float =
        timelinePlayheadFractionFromWaveformX(localXPx, waveformTimelineWidthPx)

    fun xPxForFraction(fraction: Float): Float =
        timelinePlayheadXInWaveformArea(fraction, waveformTimelineWidthPx)
}

@Composable
fun rememberTimelinePlayheadWaveformMetrics(waveformAreaWidth: Dp): TimelinePlayheadWaveformMetrics {
    val widthPx = with(LocalDensity.current) { waveformAreaWidth.toPx() }
    return remember(widthPx) {
        TimelinePlayheadWaveformMetrics(waveformTimelineWidthPx = widthPx)
    }
}

/**
 * Mirrors [TrackCard] horizontal chrome: timeline column, gap, fader — so the scrubber and
 * per-track playheads share the same waveform timeline width as the track list.
 */
@Composable
fun TimelinePlayheadTrackRowSlot(
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
                BoxWithConstraintsPlayheadWaveformSlot(waveformContent)
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
    waveformContent: @Composable BoxScope.(TimelinePlayheadWaveformMetrics) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .weight(TimelineWaveformWidthFraction)
            .fillMaxHeight(),
    ) {
        val metrics = rememberTimelinePlayheadWaveformMetrics(maxWidth)
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            waveformContent(metrics)
        }
    }
}

@Composable
fun TimelinePlayheadMarker(
    fraction: Float,
    modifier: Modifier = Modifier,
    showTopHandle: Boolean = false,
    lineWidth: Dp = 1.dp,
    handleWidth: Dp = 10.dp,
    handleHeight: Dp = 8.dp,
) {
    val clampedFraction = fraction.coerceIn(0f, 1f)
    val lineColor = AppColors.Red
    val density = LocalDensity.current
    val lineWidthPx = with(density) { lineWidth.toPx() }
    val handleWidthPx = with(density) { handleWidth.toPx() }
    val handleHeightPx = with(density) { handleHeight.toPx() }

    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val x = size.width * clampedFraction
        drawLine(
            color = lineColor,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = lineWidthPx,
        )

        if (showTopHandle) {
            val path =
                Path().apply {
                    moveTo(x - handleWidthPx / 2f, 0f)
                    lineTo(x + handleWidthPx / 2f, 0f)
                    lineTo(x, handleHeightPx)
                    close()
                }
            drawPath(path, color = lineColor)
        }
    }
}

