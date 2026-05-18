package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

private const val TimelineRulerMaxMinorTicks = 60
private const val TimelineRulerMinorTickHeightFraction = 0.35f
private const val TimelineRulerMajorTickHeightFraction = 0.62f
private const val TimelineRulerClipTickHeightFraction = 0.72f
internal const val TimelineRulerLabelMaxWidthDp = 24f

data class TimelineRulerTick(
    val timeMs: Long,
    val positionFraction: Float,
    val isMajor: Boolean,
)

data class TimelineRulerBoundaryLabel(
    val text: String,
    val fraction: Float,
    val alignToEnd: Boolean,
)

data class TimelineRulerIntervals(
    val minorIntervalMs: Long,
    val majorIntervalMs: Long,
)

fun timelineClipEndFraction(layout: TimelineClipLayout): Float =
    (layout.startFraction + layout.widthFraction).coerceIn(0f, 1f)

fun timelineRulerIntervals(timelineBaseDurationMs: Long): TimelineRulerIntervals? {
    if (timelineBaseDurationMs <= 0L) return null

    var minorMs = 5_000L
    var majorMs = 30_000L
    when {
        timelineBaseDurationMs <= 10_000L -> {
            minorMs = 1_000L
            majorMs = 2_000L
        }
        timelineBaseDurationMs <= 30_000L -> {
            minorMs = 1_000L
            majorMs = 5_000L
        }
        timelineBaseDurationMs <= 60_000L -> {
            minorMs = 2_000L
            majorMs = 10_000L
        }
        timelineBaseDurationMs <= 180_000L -> {
            minorMs = 5_000L
            majorMs = 30_000L
        }
        timelineBaseDurationMs <= 600_000L -> {
            minorMs = 10_000L
            majorMs = 60_000L
        }
        else -> {
            minorMs = 30_000L
            majorMs = 120_000L
        }
    }

    if (majorMs % minorMs != 0L) {
        majorMs = ((majorMs / minorMs) + 1L) * minorMs
    }

    while (timelineBaseDurationMs / minorMs > TimelineRulerMaxMinorTicks) {
        minorMs *= 2L
        majorMs *= 2L
    }

    return TimelineRulerIntervals(minorIntervalMs = minorMs, majorIntervalMs = majorMs)
}

/** Major ticks whose time labels fit on the scrubber panel without overlapping. */
fun scrubberMajorTicksForLabels(
    ticks: List<TimelineRulerTick>,
    minLabelSpacingFraction: Float = 0.11f,
): List<TimelineRulerTick> {
    val majors = ticks.filter { it.isMajor }
    if (majors.isEmpty()) return emptyList()
    if (majors.size == 1) return majors

    val spacing = minLabelSpacingFraction.coerceIn(0.05f, 0.5f)
    val visible = mutableListOf(majors.first())
    for (index in 1 until majors.lastIndex) {
        val tick = majors[index]
        if (tick.positionFraction - visible.last().positionFraction >= spacing) {
            visible += tick
        }
    }
    val last = majors.last()
    if (visible.last() != last) {
        if (last.positionFraction - visible.last().positionFraction < spacing * 0.65f) {
            visible.removeAt(visible.lastIndex)
        }
        visible += last
    }
    return visible
}

fun buildTimelineRulerTicks(timelineBaseDurationMs: Long): List<TimelineRulerTick> {
    val intervals = timelineRulerIntervals(timelineBaseDurationMs) ?: return emptyList()
    if (timelineBaseDurationMs <= 0L) return emptyList()

    val ticks = ArrayList<TimelineRulerTick>()
    var timeMs = 0L
    while (timeMs <= timelineBaseDurationMs) {
        val isMajor = timeMs % intervals.majorIntervalMs == 0L
        ticks += TimelineRulerTick(
            timeMs = timeMs,
            positionFraction = (timeMs.toDouble() / timelineBaseDurationMs.toDouble())
                .toFloat()
                .coerceIn(0f, 1f),
            isMajor = isMajor,
        )
        if (timeMs == timelineBaseDurationMs) break
        val next = timeMs + intervals.minorIntervalMs
        timeMs = if (next > timelineBaseDurationMs) timelineBaseDurationMs else next
    }
    return ticks
}

internal fun timelineRulerLabelStartInset(): Dp = Dimens.MediumRadius + Dimens.Stroke

internal fun rulerClipLabelOffsetX(
    fraction: Float,
    rulerWidth: Dp,
    alignToEnd: Boolean,
): Dp {
    val anchor = rulerWidth * fraction.coerceIn(0f, 1f)
    val labelWidth = TimelineRulerLabelMaxWidthDp.dp
    val startInset = timelineRulerLabelStartInset()
    return if (alignToEnd) {
        (anchor - labelWidth).coerceIn(0.dp, rulerWidth - labelWidth)
    } else {
        val minX = if (fraction <= 0.001f) startInset else Dimens.Stroke
        (anchor + Dimens.Stroke).coerceAtLeast(minX).coerceIn(minX, rulerWidth - labelWidth)
    }
}

@Composable
fun TimelineRuler(
    timelineBaseDurationMs: Long,
    clipStartFraction: Float,
    clipEndFraction: Float,
    boundaryLabels: List<TimelineRulerBoundaryLabel>,
    timelineEndFraction: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val ticks = remember(timelineBaseDurationMs) {
        buildTimelineRulerTicks(timelineBaseDurationMs)
    }
    if (ticks.isEmpty()) return

    val startFraction = clipStartFraction.coerceIn(0f, 1f)
    val clipEnd = clipEndFraction.coerceIn(startFraction, 1f)
    val timelineEnd = timelineEndFraction.coerceIn(0f, 1f)

    val minorTickColor = AppColors.Line.copy(alpha = 0.28f)
    val majorTickColor = AppColors.Line.copy(alpha = 0.5f)
    val clipLabelStyle =
        TextStyle(
            color = AppColors.Line.copy(alpha = 0.78f),
            fontSize = 6.sp,
            fontFamily = FontFamily.Monospace,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(AppColors.Bg),
    ) {
        val rulerWidth = maxWidth
        Canvas(modifier = Modifier.fillMaxSize()) {
            val baselineY = size.height
            ticks.forEach { tick ->
                val x = size.width * tick.positionFraction
                val tickHeight =
                    size.height *
                        if (tick.isMajor) {
                            TimelineRulerMajorTickHeightFraction
                        } else {
                            TimelineRulerMinorTickHeightFraction
                        }
                drawLine(
                    color = if (tick.isMajor) majorTickColor else minorTickColor,
                    start = Offset(x, baselineY),
                    end = Offset(x, baselineY - tickHeight),
                    strokeWidth = 1f,
                )
            }

            val boundaryFractions =
                if (clipEnd < timelineEnd - 0.001f) {
                    listOf(startFraction, clipEnd, timelineEnd)
                } else {
                    listOf(startFraction, clipEnd)
                }
            boundaryFractions.forEach { fraction ->
                val x = size.width * fraction
                val tickHeight = size.height * TimelineRulerClipTickHeightFraction
                drawLine(
                    color = majorTickColor,
                    start = Offset(x, baselineY),
                    end = Offset(x, baselineY - tickHeight),
                    strokeWidth = 1f,
                )
            }
        }

        if (timelineBaseDurationMs > 0L) {
            boundaryLabels.forEach { label ->
                Text(
                    text = label.text,
                    style = clipLabelStyle,
                    lineHeight = 6.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(
                            x = rulerClipLabelOffsetX(
                                fraction = label.fraction,
                                rulerWidth = rulerWidth,
                                alignToEnd = label.alignToEnd,
                            ),
                        ),
                )
            }
        }
    }
}
