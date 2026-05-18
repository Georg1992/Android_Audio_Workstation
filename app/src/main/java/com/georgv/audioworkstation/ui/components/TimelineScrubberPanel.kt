package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

private val ScrubberPanelShape = RoundedCornerShape(Dimens.TileRadius)

private const val ScrubberRulerMinorTickHeightFraction = 0.5f
private const val ScrubberRulerMajorTickHeightFraction = 1f
private const val ScrubberRulerLabelBandFraction = 0.38f
private const val ScrubberMinLabelSpacingFraction = 0.11f

@Composable
fun TimelinePlayheadScrubberPanel(
    playheadFraction: Float,
    timelineBaseDurationMs: Long,
    onPlayheadFractionPreview: (Float) -> Unit,
    onPlayheadFractionCommit: (Float) -> Unit,
    inputLocked: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding)
            .height(Dimens.PanelPlaceholderHeight)
            .background(AppColors.SurfacePanel, ScrubberPanelShape)
            .border(Dimens.Stroke, AppColors.Line, ScrubberPanelShape)
            .padding(Dimens.TileInnerPadding),
    ) {
        TimelinePlayheadScrubberTrackRow(
            modifier = Modifier.fillMaxSize(),
            timelineBaseDurationMs = timelineBaseDurationMs,
            playheadFraction = playheadFraction,
            onPlayheadFractionPreview = onPlayheadFractionPreview,
            onPlayheadFractionCommit = onPlayheadFractionCommit,
            inputLocked = inputLocked,
        )
    }
}

@Composable
private fun TimelinePlayheadScrubberTrackRow(
    timelineBaseDurationMs: Long,
    playheadFraction: Float,
    onPlayheadFractionPreview: (Float) -> Unit,
    onPlayheadFractionCommit: (Float) -> Unit,
    inputLocked: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        TimelineScrubberTimelineContent(
            timelineBaseDurationMs = timelineBaseDurationMs,
            playheadFraction = playheadFraction,
            onPlayheadFractionPreview = onPlayheadFractionPreview,
            onPlayheadFractionCommit = onPlayheadFractionCommit,
            inputLocked = inputLocked,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        Spacer(Modifier.width(Dimens.Gap))
        Spacer(Modifier.width(Dimens.FaderWidth))
    }
}

@Composable
private fun TimelineScrubberTimelineContent(
    timelineBaseDurationMs: Long,
    playheadFraction: Float,
    onPlayheadFractionPreview: (Float) -> Unit,
    onPlayheadFractionCommit: (Float) -> Unit,
    inputLocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val ticks = remember(timelineBaseDurationMs) {
        buildTimelineRulerTicks(timelineBaseDurationMs)
    }

    Row(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(TimelineWaveformWidthFraction)
                .fillMaxHeight(),
        ) {
            val metrics = rememberTimelinePlayheadWaveformMetrics(maxWidth)
            val rulerWidth = maxWidth
            val labeledMajorTicks =
                remember(ticks, rulerWidth) {
                    val spacingFraction =
                        (TimelineRulerLabelMaxWidthDp.dp.value / rulerWidth.value)
                            .coerceIn(ScrubberMinLabelSpacingFraction, 0.45f)
                    scrubberMajorTicksForLabels(ticks, spacingFraction)
                }
            val labelStyle =
                TextStyle(
                    color = AppColors.Line.copy(alpha = 0.82f),
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                )

            if (ticks.isNotEmpty()) {
                val minorTickColor = AppColors.Line.copy(alpha = 0.28f)
                val majorTickColor = AppColors.Line.copy(alpha = 0.52f)
                val density = LocalDensity.current
                val labelBandPx = with(density) { (maxHeight * ScrubberRulerLabelBandFraction).toPx() }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val baselineY = size.height - labelBandPx
                    ticks.forEach { tick ->
                        val x = size.width * tick.positionFraction
                        val tickHeight =
                            baselineY *
                                if (tick.isMajor) {
                                    ScrubberRulerMajorTickHeightFraction
                                } else {
                                    ScrubberRulerMinorTickHeightFraction
                                }
                        drawLine(
                            color = if (tick.isMajor) majorTickColor else minorTickColor,
                            start = Offset(x, baselineY),
                            end = Offset(x, baselineY - tickHeight),
                            strokeWidth = 1f,
                        )
                    }
                }
            }

            labeledMajorTicks.forEach { tick ->
                val alignToEnd = tick.positionFraction > 0.9f
                Text(
                    text = formatTimelineDuration(tick.timeMs),
                    style = labelStyle,
                    lineHeight = 7.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(
                            x = rulerClipLabelOffsetX(
                                fraction = tick.positionFraction,
                                rulerWidth = rulerWidth,
                                alignToEnd = alignToEnd,
                            ),
                        ),
                )
            }

            TimelinePlayheadScrubberWaveformArea(
                playheadFraction = playheadFraction,
                metrics = metrics,
                onPlayheadFractionPreview = onPlayheadFractionPreview,
                onPlayheadFractionCommit = onPlayheadFractionCommit,
                inputLocked = inputLocked,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Spacer(
            modifier = Modifier
                .weight(TimelineMetadataWidthFraction)
                .fillMaxHeight(),
        )
    }
}
