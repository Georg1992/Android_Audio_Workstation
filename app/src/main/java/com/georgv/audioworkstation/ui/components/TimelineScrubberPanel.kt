package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.georgv.audioworkstation.core.audio.MasterPeakIndicatorLevel
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppOpacity
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

private val ScrubberPanelShape = RoundedCornerShape(Dimens.TileRadius)

private const val ScrubberRulerMinorTickHeightFraction = 0.58f
private const val ScrubberRulerMajorTickHeightFraction = 1f
private const val ScrubberRulerLabelBandFraction = 0.30f
private const val ScrubberMinLabelSpacingFraction = 0.11f
private const val ScrubberRulerMinorTickAlpha = 0.38f
private const val ScrubberRulerMajorTickAlpha = 0.64f
private const val ScrubberRulerMajorTickStrokePx = 1.25f
private val ScrubberUtilityZoneBackground = AppColors.SurfacePressed
private val ScrubberUtilityZoneDividerWidth = 2.dp
private val ScrubberUtilityZoneInsetShadowWidth = 3.dp

@Composable
fun TimelinePlayheadScrubberPanel(
    playheadPositionMs: Long,
    timelineDurationMs: Long,
    masterPeakDbText: String = "0 dB",
    masterPeakIndicatorLevel: MasterPeakIndicatorLevel = MasterPeakIndicatorLevel.Inactive,
    onMasterPeakIndicatorClick: () -> Unit = {},
    onPlayheadScrubStarted: () -> Unit = {},
    onPlayheadScrubCancelled: () -> Unit = {},
    onPlayheadPositionPreview: (Long) -> Unit,
    onPlayheadPositionCommit: (Long) -> Unit,
    inputLocked: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding)
            .height(Dimens.PanelPlaceholderHeight)
            .background(AppColors.SurfacePanel, ScrubberPanelShape)
            .border(Dimens.Stroke, AppColors.Line, ScrubberPanelShape),
    ) {
        val utilityZoneWidth =
            (maxWidth - Dimens.Gap - Dimens.FaderWidth) * TimelineMetadataWidthFraction +
                Dimens.Gap +
                Dimens.FaderWidth

        TimelinePlayheadTrackRowSlot(
            timelineDurationMs = timelineDurationMs,
            modifier = Modifier.fillMaxSize(),
        ) { metrics ->
            TimelineScrubberWaveformSlot(
                metrics = metrics,
                timelineDurationMs = timelineDurationMs,
                playheadPositionMs = playheadPositionMs,
                onPlayheadScrubStarted = onPlayheadScrubStarted,
                onPlayheadScrubCancelled = onPlayheadScrubCancelled,
                onPlayheadPositionPreview = onPlayheadPositionPreview,
                onPlayheadPositionCommit = onPlayheadPositionCommit,
                inputLocked = inputLocked,
            )
        }
        TimelinePlayheadUtilityStrip(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(utilityZoneWidth)
                    .fillMaxHeight()
                    .alpha(if (inputLocked) AppOpacity.disabled else 1f),
        ) {
            MasterOutputPeakIndicator(
                peakDbText = masterPeakDbText,
                indicatorLevel = masterPeakIndicatorLevel,
                onClick = onMasterPeakIndicatorClick,
            )
        }
    }
}

/**
 * Right-side status/utility column aligned with track-card metadata + fader chrome.
 * Additional meters (CPU, latency, project info) can be added as sibling composables here.
 */
@Composable
private fun TimelinePlayheadUtilityStrip(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(ScrubberUtilityZoneBackground)
                    .drawBehind {
                        val insetPx = ScrubberUtilityZoneInsetShadowWidth.toPx()
                        if (insetPx > 0f) {
                            drawRect(
                                color = Color.Black.copy(alpha = 0.07f),
                                size = Size(insetPx, size.height),
                            )
                        }
                    },
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .width(ScrubberUtilityZoneDividerWidth)
                    .fillMaxHeight()
                    .background(AppColors.Line),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = Dimens.TightGap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/**
 * Ruler ticks, scrub hit-testing, and the top playhead marker share one waveform-column [Box].
 * [TimelinePlayheadMarker] is the only top playhead visual; X uses [timelineMsToX].
 */
@Composable
private fun TimelineScrubberWaveformSlot(
    metrics: TimelinePlayheadWaveformMetrics,
    timelineDurationMs: Long,
    playheadPositionMs: Long,
    onPlayheadScrubStarted: () -> Unit,
    onPlayheadScrubCancelled: () -> Unit,
    onPlayheadPositionPreview: (Long) -> Unit,
    onPlayheadPositionCommit: (Long) -> Unit,
    inputLocked: Boolean,
) {
    val ticks = remember(timelineDurationMs) {
        buildTimelineRulerTicks(timelineDurationMs)
    }
    val density = LocalDensity.current
    val rulerWidth = with(density) { metrics.waveformTimelineWidthPx.toDp() }
    val labeledMajorTicks =
        remember(ticks, rulerWidth, timelineDurationMs) {
            val spacingFraction =
                (TimelineRulerLabelMaxWidthDp.dp.value / rulerWidth.value)
                    .coerceIn(ScrubberMinLabelSpacingFraction, 0.45f)
            scrubberMajorTicksForLabels(ticks, spacingFraction)
        }
    val labelStyle =
        TextStyle(
            color = AppColors.labelEmphasis,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )

    Box(modifier = Modifier.fillMaxSize()) {
        if (ticks.isNotEmpty()) {
            val minorTickColor = AppColors.Line.copy(alpha = ScrubberRulerMinorTickAlpha)
            val majorTickColor = AppColors.Line.copy(alpha = ScrubberRulerMajorTickAlpha)

            Canvas(modifier = Modifier.fillMaxSize()) {
                val labelBandHeightPx = size.height * ScrubberRulerLabelBandFraction
                val baselineY = size.height - labelBandHeightPx
                ticks.forEach { tick ->
                    val x =
                        timelineMsToX(
                            timeMs = tick.timeMs,
                            timelineDurationMs = timelineDurationMs,
                            contentWidthPx = size.width,
                        )
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
                        strokeWidth = if (tick.isMajor) ScrubberRulerMajorTickStrokePx else 1f,
                    )
                }
            }
        }

        labeledMajorTicks.forEach { tick ->
            Text(
                text = formatTimelineDuration(tick.timeMs),
                style = labelStyle,
                lineHeight = 7.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(
                        x = scrubberRulerLabelOffsetX(
                            timeMs = tick.timeMs,
                            timelineDurationMs = timelineDurationMs,
                            metrics = metrics,
                            rulerWidth = rulerWidth,
                            density = density,
                        ),
                    ),
            )
        }

        TimelinePlayheadScrubberWaveformArea(
            metrics = metrics,
            onPlayheadScrubStarted = onPlayheadScrubStarted,
            onPlayheadScrubCancelled = onPlayheadScrubCancelled,
            onPlayheadPositionPreview = onPlayheadPositionPreview,
            onPlayheadPositionCommit = onPlayheadPositionCommit,
            inputLocked = inputLocked,
            modifier = Modifier.fillMaxSize(),
        )

        TimelinePlayheadMarker(
            playheadPositionMs = playheadPositionMs,
            timelineDurationMs = timelineDurationMs,
            showTopHandle = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Scrubber ruler labels share [timelineMsToX] with tick lines and the playhead marker. */
private fun scrubberRulerLabelOffsetX(
    timeMs: Long,
    timelineDurationMs: Long,
    metrics: TimelinePlayheadWaveformMetrics,
    rulerWidth: Dp,
    density: Density,
): Dp {
    val anchorPx =
        timelineMsToX(
            timeMs = timeMs,
            timelineDurationMs = timelineDurationMs,
            contentWidthPx = metrics.waveformTimelineWidthPx,
            contentStartPx = metrics.contentStartPx,
        )
    val alignToEnd =
        timelineDurationMs > 0L && timeMs > timelineDurationMs * 9L / 10L
    val anchor = with(density) { anchorPx.toDp() }
    val labelWidth = TimelineRulerLabelMaxWidthDp.dp
    val startInset = timelineRulerLabelStartInset()
    return if (alignToEnd) {
        (anchor - labelWidth).coerceIn(0.dp, rulerWidth - labelWidth)
    } else {
        val minX = if (timeMs <= 0L) startInset else Dimens.Stroke
        (anchor + Dimens.Stroke).coerceAtLeast(minX).coerceIn(minX, rulerWidth - labelWidth)
    }
}
