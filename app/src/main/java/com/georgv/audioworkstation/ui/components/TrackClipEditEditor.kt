package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.waveform.WaveformPeaks
import com.georgv.audioworkstation.core.track.applyClipPositionMoveDrag
import com.georgv.audioworkstation.core.track.loopRegionOverlayFractions
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun TrackClipTrimWaveformEditor(
    peaks: WaveformPeaks,
    sourceDurationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    onTrimCommit: (trimStartMs: Long, trimEndMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sourceDurationMs <= 0L) return

    val trimFractions =
        remember(trimStartMs, trimEndMs, sourceDurationMs) {
            loopRegionOverlayFractions(trimStartMs, trimEndMs, sourceDurationMs)
        }
    val sourceFitScale =
        remember(sourceDurationMs) {
            TimelineLaneScale(
                mode = TimelineLaneScaleMode.SourceFitWhileEditing,
                sourceDurationMs = sourceDurationMs.coerceAtLeast(1L),
                laneLayoutDurationMs = sourceDurationMs.coerceAtLeast(1L),
                clipStartOffsetMs = 0L,
                clipDurationMs = sourceDurationMs.coerceAtLeast(1L),
            )
        }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(AppColors.Bg)
                .border(Dimens.Stroke, AppColors.Line, RoundedCornerShape(Dimens.MediumRadius)),
    ) {
        val density = LocalDensity.current
        val areaWidthPx = with(density) { maxWidth.toPx() }

        TrackWaveform(
            modifier = Modifier.fillMaxSize(),
            peaks = peaks,
            loopRegionStartFraction = trimFractions.startFraction,
            loopRegionEndFraction = trimFractions.endFraction,
        )

        LoopRegionEditor(
            sourceDurationMs = sourceDurationMs,
            loopStartMs = trimStartMs,
            loopEndMs = trimEndMs,
            editingEnabled = true,
            onLoopRegionCommit = onTrimCommit,
            waveformAreaWidthPx = areaWidthPx,
            timelineScale = sourceFitScale,
            sourceFitScale = sourceFitScale,
            loopEditFocusActive = false,
            persistentViewportZoomed = true,
            onLoopRegionEditFocusChanged = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun TrackClipTimelinePositionEditor(
    timelineLayoutDurationMs: Long,
    clipStartOffsetMs: Long,
    trimmedDurationMs: Long,
    onClipPositionCommit: (clipStartOffsetMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (timelineLayoutDurationMs <= 0L || trimmedDurationMs <= 0L) return

    var previewClipStartMs by remember { mutableLongStateOf(clipStartOffsetMs) }
    var isDragging by remember { mutableStateOf(false) }

    val latestClipStartOffsetMs by rememberUpdatedState(clipStartOffsetMs)
    val latestTimelineLayoutDurationMs by rememberUpdatedState(timelineLayoutDurationMs)
    val latestTrimmedDurationMs by rememberUpdatedState(trimmedDurationMs)
    val latestOnClipPositionCommit by rememberUpdatedState(onClipPositionCommit)

    androidx.compose.runtime.LaunchedEffect(latestClipStartOffsetMs, isDragging) {
        if (!isDragging) {
            previewClipStartMs = latestClipStartOffsetMs
        }
    }

    val clipStartFraction =
        (previewClipStartMs.toDouble() / timelineLayoutDurationMs.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    val clipEndFraction =
        ((previewClipStartMs + trimmedDurationMs).toDouble() / timelineLayoutDurationMs.toDouble())
            .toFloat()
            .coerceIn(clipStartFraction, 1f)

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(AppColors.Bg)
                .border(Dimens.Stroke, AppColors.Line, RoundedCornerShape(Dimens.MediumRadius)),
    ) {
        val density = LocalDensity.current
        val areaWidthPx = with(density) { maxWidth.toPx() }
        val clipStartPx = areaWidthPx * clipStartFraction
        val clipWidthPx = areaWidthPx * (clipEndFraction - clipStartFraction).coerceAtLeast(0f)
        val clipOffset = with(density) { clipStartPx.toDp() }
        val clipWidth = with(density) { clipWidthPx.toDp().coerceAtLeast(3.dp) }

        TimelineRuler(
            timelineBaseDurationMs = timelineLayoutDurationMs,
            clipStartFraction = clipStartFraction,
            clipEndFraction = clipEndFraction,
            boundaryLabels =
                listOf(
                    TimelineRulerBoundaryLabel(
                        text = formatTimelineDuration(0L),
                        fraction = 0f,
                        alignToEnd = false,
                    ),
                    TimelineRulerBoundaryLabel(
                        text = formatTimelineDuration(timelineLayoutDurationMs),
                        fraction = 1f,
                        alignToEnd = true,
                    ),
                ),
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier =
                Modifier
                    .offset(x = clipOffset)
                    .width(clipWidth)
                    .fillMaxHeight()
                    .background(AppColors.LoopRegionFill.copy(alpha = 0.55f))
                    .border(Dimens.Stroke, AppColors.Line)
                    .pointerInput(timelineLayoutDurationMs, trimmedDurationMs) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            if (areaWidthPx <= 0f) return@awaitEachGesture

                            val clipStartAtDown = previewClipStartMs
                            isDragging = true
                            var accumulatedDeltaPx = 0f

                            drag(down.id) { change ->
                                change.consume()
                                accumulatedDeltaPx += change.position.x - change.previousPosition.x
                                val deltaMs =
                                    (accumulatedDeltaPx.toDouble() / areaWidthPx.toDouble() *
                                        latestTimelineLayoutDurationMs.toDouble())
                                        .toLong()
                                previewClipStartMs =
                                    applyClipPositionMoveDrag(
                                        deltaMs = deltaMs,
                                        clipStartOffsetMs = clipStartAtDown,
                                        trimmedDurationMs = latestTrimmedDurationMs,
                                        timelineLayoutDurationMs = latestTimelineLayoutDurationMs,
                                    )
                            }

                            isDragging = false
                            latestOnClipPositionCommit(previewClipStartMs)
                        }
                    },
        )
    }
}

@Composable
fun TrackEditFxPlaceholderCard(
    title: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(AppColors.Bg, shape)
                .border(Dimens.Stroke, AppColors.Line, shape)
                .padding(Dimens.PanelPadding),
    ) {
        Text(
            text = title,
            style = AppText.TopBarTitle.copy(fontSize = AppText.TopBarTitle.fontSize * 0.85f),
            color = AppColors.Line,
        )
        Text(
            text = stringResource(R.string.track_edit_coming_soon),
            style = AppText.TopBarTitle.copy(fontSize = AppText.TopBarTitle.fontSize * 0.75f),
            color = AppColors.Line.copy(alpha = 0.65f),
            modifier = Modifier.padding(top = Dimens.Gap + Dimens.PanelPadding),
        )
    }
}
