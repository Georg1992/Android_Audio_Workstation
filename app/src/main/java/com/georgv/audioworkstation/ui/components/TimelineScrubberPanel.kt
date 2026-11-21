package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.georgv.audioworkstation.core.audio.MasterPeakIndicatorLevel
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppOpacity
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

private val ScrubberPanelShape = RoundedCornerShape(Dimens.TileRadius)

private const val TimelineTrackAlpha = 0.35f
private const val TimelineTickAlpha = 0.72f
private const val TimelineMinorTickAlpha = 0.4f
private const val TimelineTrackCenterFraction = 0.32f
private const val TimelineTickHalfFraction = 0.1f
private const val TimelineMinorTickHalfFraction = 0.05f
private val TimelineTrackStroke = 1.dp
private val TimelinePlayheadWidth = 1.dp
private val TimelinePlayheadCapRadius = 3.dp
private val TimelineMarkSize = 10.sp
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
            .height(Dimens.TransportTimelineHeight)
            .shadow(elevation = Dimens.PanelElevation, shape = ScrubberPanelShape, clip = false)
            .background(AppColors.SurfaceRaised, ScrubberPanelShape),
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
                    .zIndex(1f)
                    .alpha(if (inputLocked) AppOpacity.disabled else 1f),
            onClick = onMasterPeakIndicatorClick,
        ) {
            MasterOutputPeakIndicator(
                peakDbText = masterPeakDbText,
                indicatorLevel = masterPeakIndicatorLevel,
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
    onClick: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier =
            modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
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
                    .background(AppColors.SurfacePressed),
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

@Composable
internal fun MixTransportTimeline(
    playheadPositionMs: Long,
    timelineDurationMs: Long,
    gestures: MixTimelineGestures,
    inputLocked: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val metrics = rememberTimelinePlayheadWaveformMetrics(
            waveformAreaWidth = maxWidth,
            timelineDurationMs = timelineDurationMs,
        )
        TimelineScrubberWaveformSlot(
            metrics = metrics,
            timelineDurationMs = timelineDurationMs,
            playheadPositionMs = playheadPositionMs,
            onPlayheadScrubStarted = gestures.onStarted,
            onPlayheadScrubCancelled = gestures.onCancelled,
            onPlayheadPositionPreview = gestures.onPreview,
            onPlayheadPositionCommit = gestures.onCommit,
            inputLocked = inputLocked,
        )
    }
}

internal class MixTimelineGestures(
    val onStarted: () -> Unit,
    val onCancelled: () -> Unit,
    val onPreview: (Long) -> Unit,
    val onCommit: (Long) -> Unit,
)

@Composable
internal fun TransportTimeline(
    playheadPositionMs: Long,
    timelineDurationMs: Long,
    gestures: MixTimelineGestures,
    inputLocked: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val metrics = rememberTimelinePlayheadWaveformMetrics(
            waveformAreaWidth = maxWidth,
            timelineDurationMs = timelineDurationMs,
        )
        TimelineScrubberWaveformSlot(
            metrics = metrics,
            timelineDurationMs = timelineDurationMs,
            playheadPositionMs = playheadPositionMs,
            onPlayheadScrubStarted = gestures.onStarted,
            onPlayheadScrubCancelled = gestures.onCancelled,
            onPlayheadPositionPreview = gestures.onPreview,
            onPlayheadPositionCommit = gestures.onCommit,
            inputLocked = inputLocked,
        )
    }
}

/**
 * One thin track, time sections, and the red playhead share the waveform column.
 * The playhead is the top layer. [TimelinePlayheadMarker] is the only playhead; X uses [timelineMsToX].
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
        buildScrubberRulerTicks(timelineDurationMs)
    }
    val marks = remember(ticks) { scrubberMajorTicksForLabels(ticks) }
    val density = LocalDensity.current
    val rulerWidth = with(density) { metrics.waveformTimelineWidthPx.toDp() }
    val markStyle = AppText.TileSubtitle.copy(color = AppColors.Line, fontSize = TimelineMarkSize)
    val playheadWidthPx = with(density) { TimelinePlayheadWidth.toPx() }
    val playheadCapRadiusPx = with(density) { TimelinePlayheadCapRadius.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                val x = timelineMsToX(
                    timeMs = playheadPositionMs,
                    timelineDurationMs = timelineDurationMs,
                    contentWidthPx = size.width,
                )
                drawScrubberPlayhead(
                    x = x,
                    lineWidthPx = playheadWidthPx,
                    capRadiusPx = playheadCapRadiusPx,
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackY = size.height * TimelineTrackCenterFraction
            val stroke = TimelineTrackStroke.toPx()
            drawLine(
                color = AppColors.Line.copy(alpha = TimelineTrackAlpha),
                start = Offset(0f, trackY),
                end = Offset(size.width, trackY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            ticks.forEach { tick ->
                val tickHalf = size.height * if (tick.isMajor) {
                    TimelineTickHalfFraction
                } else {
                    TimelineMinorTickHalfFraction
                }
                val x = timelineMsToX(
                    timeMs = tick.timeMs,
                    timelineDurationMs = timelineDurationMs,
                    contentWidthPx = size.width,
                )
                drawLine(
                    color = AppColors.Line.copy(
                        alpha = if (tick.isMajor) TimelineTickAlpha else TimelineMinorTickAlpha,
                    ),
                    start = Offset(x, trackY - tickHalf),
                    end = Offset(x, trackY + tickHalf),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
        marks.forEach { tick ->
            Text(
                text = formatTimelineDuration(tick.timeMs),
                style = markStyle,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = timelineMarkOffset(tick.timeMs, timelineDurationMs, metrics, rulerWidth, density))
                    .padding(bottom = Dimens.TightGap),
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
    }
}

private fun timelineMarkOffset(
    timeMs: Long,
    timelineDurationMs: Long,
    metrics: TimelinePlayheadWaveformMetrics,
    rulerWidth: Dp,
    density: Density,
): Dp {
    val anchorPx = timelineMsToX(
        timeMs = timeMs,
        timelineDurationMs = timelineDurationMs,
        contentWidthPx = metrics.waveformTimelineWidthPx,
        contentStartPx = metrics.contentStartPx,
    )
    val anchor = with(density) { anchorPx.toDp() }
    val labelWidth = TimelineRulerLabelMaxWidthDp.dp
    val end = (rulerWidth - labelWidth).coerceAtLeast(0.dp)
    return (anchor - labelWidth / 2).coerceIn(0.dp, end)
}
