package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.core.track.effectiveLoopEndMs
import com.georgv.audioworkstation.core.track.effectiveLoopStartMs
import com.georgv.audioworkstation.core.track.hasPersistedPlayableAudio
import com.georgv.audioworkstation.core.track.trackLocalPlayheadVisibleInClip
import com.georgv.audioworkstation.core.track.trackSourcePlayheadMs
import com.georgv.audioworkstation.core.track.trackSourcePlayheadMsForClipTimelineWindow
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlin.math.max
import kotlin.math.min

/** Viewport/ruler sanity cap for playback scrub and layout — not a recording duration limit. */
const val TimelineMaxDurationMs = 10 * 60 * 1000L
const val TimelineMinimumBaseDurationMs = 1L
const val TimelineClipMinimumWidthDp = 3f
const val TimelineMetadataWidthFraction = 0.12f
const val TimelineWaveformWidthFraction = 1f - TimelineMetadataWidthFraction

const val TimelineRulerHeightFraction = 0.2f
const val TimelineWaveformHeightFraction = 1f - TimelineRulerHeightFraction

fun timelineLaneTotalHeightDp(): Dp =
    Dimens.PlaceholderHeight / TimelineWaveformHeightFraction

sealed interface WaveformState {
    data object NoWaveform : WaveformState
    data object Loading : WaveformState
    data class Ready(val peaks: WaveformPeaks) : WaveformState
    data object Failed : WaveformState
}

data class TimelineClip(
    val clipId: String,
    val laneId: String,
    val startOffsetMs: Long,
    val durationMs: Long,
    val waveformState: WaveformState,
    val isTimelineBase: Boolean,
    val formattedDuration: String,
    val channelCount: Int = 1,
    val isActiveRecording: Boolean = false,
    val isLoop: Boolean = false,
    val loopStartMs: Long = 0L,
    val loopEndMs: Long? = null,
    /** Track-local active region start (ms from WAV start). */
    val effectiveStartMs: Long = 0L,
    /** Track-local active region end (ms from WAV start). */
    val effectiveEndMs: Long = 0L,
)

data class TimelineClipLayout(
    val startFraction: Float,
    val widthFraction: Float,
)

data class TimelineLaneLayout(
    val laneWidthDp: Float,
    val waveformAreaWidthDp: Float,
    val metadataWidthDp: Float,
)

fun timelineClipEndMs(startOffsetMs: Long, durationMs: Long): Long {
    val start = startOffsetMs.coerceAtLeast(0L)
    val duration = durationMs.coerceAtLeast(0L)
    return start + duration
}

fun timelineClipEffectiveTimelineEndMs(clip: TimelineClip): Long =
    timelineClipEndMs(clip.startOffsetMs, clip.durationMs)

fun projectTimelineClips(
    tracks: List<TrackEntity>,
    waveformStatesByTrackId: Map<String, WaveformState>,
): List<TimelineClip> {
    val playableTracks = tracks.mapNotNull { track ->
        val durationMs = track.duration?.takeIf { it > 0L } ?: return@mapNotNull null
        if (track.wavFilePath.isBlank()) return@mapNotNull null
        if (track.isRecording && !track.hasPersistedPlayableAudio()) return@mapNotNull null
        val startOffsetMs = track.timelineStartOffsetMs.coerceAtLeast(0L)
        track to TimelineClipSpan(startOffsetMs = startOffsetMs, durationMs = durationMs.coerceAtLeast(0L))
    }
    if (playableTracks.isEmpty()) return emptyList()
    val baseEndMs =
        playableTracks.maxOf { (_, span) ->
            timelineClipEndMs(span.startOffsetMs, span.durationMs)
        }
    return playableTracks.map { (track, span) ->
        val clipEffectiveEnd = timelineClipEndMs(span.startOffsetMs, span.durationMs)
        TimelineClip(
            clipId = track.id,
            laneId = track.id,
            startOffsetMs = span.startOffsetMs,
            durationMs = span.durationMs,
            waveformState = waveformStatesByTrackId[track.id] ?: WaveformState.Loading,
            isTimelineBase = clipEffectiveEnd == baseEndMs,
            formattedDuration = formatTimelineDuration(span.durationMs),
            channelCount = track.channelCount.coerceIn(1, 2),
            isLoop = track.isLoop,
            loopStartMs = track.loopStartMs,
            loopEndMs = track.loopEndMs,
            effectiveStartMs = track.effectiveLoopStartMs(),
            effectiveEndMs = track.effectiveLoopEndMs(),
        )
    }
}

private data class TimelineClipSpan(
    val startOffsetMs: Long,
    val durationMs: Long,
)

/**
 * Maps full-file [WaveformPeaks] to the timeline clip width when the on-disk WAV is longer than
 * [clipDurationMs], so the visible waveform matches the audio segment the engine plays from offset 0.
 */
fun waveformPeaksForTimelineClip(
    peaks: WaveformPeaks,
    clipDurationMs: Long,
): WaveformPeaks {
    if (clipDurationMs <= 0L) return peaks
    val sourceDurationMs = peaks.sourceDurationMs
    if (sourceDurationMs <= 0L || clipDurationMs >= sourceDurationMs) return peaks

    fun visibleBarCount(totalBars: Int): Int {
        if (totalBars == 0) return 0
        return ((totalBars.toLong() * clipDurationMs) / sourceDurationMs)
            .toInt()
            .coerceIn(1, totalBars)
    }

    if (peaks.isStereo) {
        val left = peaks.leftAmplitudes.orEmpty()
        val right = peaks.rightAmplitudes.orEmpty()
        val totalBars = minOf(left.size, right.size)
        if (totalBars == 0) return peaks
        val visibleBars = visibleBarCount(totalBars)
        if (visibleBars >= totalBars) return peaks
        return peaks.copy(
            leftAmplitudes = left.take(visibleBars),
            rightAmplitudes = right.take(visibleBars),
        )
    }

    val totalBars = peaks.amplitudes.size
    if (totalBars == 0) return peaks
    val visibleBars = visibleBarCount(totalBars)
    if (visibleBars >= totalBars) return peaks
    return peaks.copy(amplitudes = peaks.amplitudes.take(visibleBars))
}

fun timelineClipLayout(
    clip: TimelineClip,
    timelineBaseDurationMs: Long,
): TimelineClipLayout? {
    if (clip.durationMs <= 0L || timelineBaseDurationMs <= 0L) return null
    val start = clip.startOffsetMs.coerceAtLeast(0L)
    if (start >= timelineBaseDurationMs) return null
    val end = min(start + clip.durationMs, timelineBaseDurationMs)
    val visibleDuration = max(0L, end - start)
    if (visibleDuration <= 0L) return null

    return TimelineClipLayout(
        startFraction = (start.toDouble() / timelineBaseDurationMs.toDouble()).toFloat().coerceIn(0f, 1f),
        widthFraction = (visibleDuration.toDouble() / timelineBaseDurationMs.toDouble()).toFloat().coerceIn(0f, 1f),
    )
}

fun timelineLaneLayout(laneWidthDp: Float): TimelineLaneLayout {
    val safeLaneWidth = laneWidthDp.coerceAtLeast(0f)
    val metadataWidthDp = safeLaneWidth * TimelineMetadataWidthFraction
    return TimelineLaneLayout(
        laneWidthDp = safeLaneWidth,
        waveformAreaWidthDp = safeLaneWidth * TimelineWaveformWidthFraction,
        metadataWidthDp = metadataWidthDp,
    )
}

fun formatTimelineDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) + 999L) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

fun timelineClipEndTimeMs(
    clip: TimelineClip,
    timelineBaseDurationMs: Long,
): Long {
    val startMs = clip.startOffsetMs.coerceAtLeast(0L)
    return min(startMs + clip.durationMs, timelineBaseDurationMs)
}

fun timelineRulerBoundaryLabels(
    clip: TimelineClip,
    layout: TimelineClipLayout,
    timelineBaseDurationMs: Long,
): List<TimelineRulerBoundaryLabel> {
    val startMs = clip.startOffsetMs.coerceAtLeast(0L)
    val clipEndMs = timelineClipEndTimeMs(clip, timelineBaseDurationMs)
    val clipEndFraction = timelineClipEndFraction(layout)

    val labels =
        listOf(
            TimelineRulerBoundaryLabel(
                text = formatTimelineDuration(startMs),
                fraction = layout.startFraction,
                alignToEnd = false,
            ),
            TimelineRulerBoundaryLabel(
                text = formatTimelineDuration(clipEndMs),
                fraction = clipEndFraction,
                alignToEnd = true,
            ),
        )

    if (clip.isTimelineBase) {
        return labels
    }

    return labels +
        TimelineRulerBoundaryLabel(
            text = formatTimelineDuration(timelineBaseDurationMs),
            fraction = 1f,
            alignToEnd = true,
        )
}

@Composable
fun TrackTimelineLane(
    clip: TimelineClip?,
    /** Fixed lane layout from base timeline (not playback-expanded global duration). */
    laneLayoutDurationMs: Long,
    /** Global project timeline for non-loop lane playhead alignment. */
    globalPlayheadTimelineDurationMs: Long,
    playheadPositionMs: Long,
    recordingInputLevel: Float? = null,
    loopRegionEditingEnabled: Boolean = false,
    onLoopRegionCommit: ((loopStartMs: Long, loopEndMs: Long) -> Unit)? = null,
    trackId: String? = null,
    hasPersistedPlayableAudio: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)
    val density = LocalDensity.current
    val playheadLineWidthPx = with(density) { 1.dp.toPx() }
    val laneLayout = clip?.let { timelineClipLayout(it, laneLayoutDurationMs) }
    val editingEnabled = loopRegionEditingEnabled && onLoopRegionCommit != null
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .heightIn(min = timelineLaneTotalHeightDp())
            .clip(shape)
            .background(AppColors.Bg)
            .border(Dimens.Stroke, AppColors.Line, shape)
    ) {
        val activeClip = clip ?: return@BoxWithConstraints
        val layout = laneLayout ?: return@BoxWithConstraints
        var loopRegionEditFocus by remember(activeClip.laneId) { mutableStateOf(false) }
        var laneViewportZoomed by remember(activeClip.laneId) { mutableStateOf(false) }
        val timelineScale =
            remember(laneLayoutDurationMs, activeClip.laneId, activeClip.durationMs, activeClip.startOffsetMs) {
                timelineLaneScaleForLoopEdit(
                    loopEditFocusActive = false,
                    laneLayoutDurationMs = laneLayoutDurationMs,
                    clip = activeClip,
                )
            }
        val sourceFitScale =
            remember(laneLayoutDurationMs, activeClip.laneId, activeClip.durationMs, activeClip.startOffsetMs) {
                timelineLaneScaleForLoopEdit(
                    loopEditFocusActive = true,
                    laneLayoutDurationMs = laneLayoutDurationMs,
                    clip = activeClip,
                )
            }
        val laneScale =
            if (timelineLaneUsesSourceFit(laneViewportZoomed, loopRegionEditFocus)) {
                sourceFitScale
            } else {
                timelineScale
            }
        val drawGlobalPlayheadInLane = !activeClip.isLoop && !laneViewportZoomed
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(TimelineWaveformWidthFraction)
                .fillMaxHeight()
                .reportLaneWaveformBounds(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(TimelineWaveformHeightFraction)
                        .fillMaxWidth(),
                ) {
                    val waveformAreaWidthPx = with(density) { maxWidth.toPx() }
                    var loopPreviewStartMs by remember(activeClip.laneId) {
                        mutableLongStateOf(activeClip.effectiveStartMs)
                    }
                    var loopPreviewEndMs by remember(activeClip.laneId) {
                        mutableLongStateOf(activeClip.effectiveEndMs)
                    }
                    LaunchedEffect(activeClip.effectiveStartMs, activeClip.effectiveEndMs) {
                        loopPreviewStartMs = activeClip.effectiveStartMs
                        loopPreviewEndMs = activeClip.effectiveEndMs
                    }
                    val clipStart = maxWidth * laneScale.clipStartFractionOnWaveformArea()
                    val clipWidth =
                        (maxWidth * laneScale.clipWidthFractionOnWaveformArea())
                            .coerceAtLeast(TimelineClipMinimumWidthDp.dp)
                    val sourcePlayheadMs =
                        trackSourcePlayheadMs(
                            globalPlayheadMs = playheadPositionMs,
                            timelineStartOffsetMs = activeClip.startOffsetMs,
                            sourceDurationMs = activeClip.durationMs,
                            loopEnabled = activeClip.isLoop,
                            loopStartMs = activeClip.effectiveStartMs,
                            loopEndMs = activeClip.effectiveEndMs,
                        )
                    val clipLocalPlayheadMs =
                        if (laneViewportZoomed) {
                            trackSourcePlayheadMsForClipTimelineWindow(
                                globalPlayheadMs = playheadPositionMs,
                                timelineStartOffsetMs = activeClip.startOffsetMs,
                                sourceDurationMs = activeClip.durationMs,
                                loopEnabled = activeClip.isLoop,
                                loopStartMs = activeClip.effectiveStartMs,
                                loopEndMs = activeClip.effectiveEndMs,
                            )
                        } else {
                            null
                        }
                    val showLocalPlayhead =
                        when {
                            laneViewportZoomed -> clipLocalPlayheadMs != null
                            activeClip.isLoop ->
                                trackLocalPlayheadVisibleInClip(
                                    globalPlayheadMs = playheadPositionMs,
                                    timelineStartOffsetMs = activeClip.startOffsetMs,
                                    clipDurationMs = activeClip.durationMs,
                                    loopEnabled = true,
                                )
                            else -> false
                        }
                    val localPlayheadSourceMs = clipLocalPlayheadMs ?: sourcePlayheadMs
                    Box(
                        modifier = Modifier
                            .offset(x = clipStart)
                            .width(clipWidth)
                            .fillMaxHeight()
                            .clip(shape)
                            .background(AppColors.SurfacePanel)
                            .padding(top = Dimens.TightGap, start = 1.dp),
                    ) {
                        when (
                            timelineLaneWaveformMode(
                                waveformState = activeClip.waveformState,
                                isActiveRecording = activeClip.isActiveRecording,
                                recordingInputLevel = recordingInputLevel,
                            )
                        ) {
                            TimelineLaneWaveformMode.PersistedPeaks -> {
                                val peaks = (activeClip.waveformState as WaveformState.Ready).peaks
                                val loopRegionStartFraction =
                                    if (activeClip.isLoop && activeClip.durationMs > 0L) {
                                        loopPreviewStartMs.toFloat() / activeClip.durationMs.toFloat()
                                    } else {
                                        null
                                    }
                                val loopRegionEndFraction =
                                    if (activeClip.isLoop && activeClip.durationMs > 0L) {
                                        loopPreviewEndMs.toFloat() / activeClip.durationMs.toFloat()
                                    } else {
                                        null
                                    }
                                TrackWaveform(
                                    peaks =
                                        waveformPeaksForTimelineClip(
                                            peaks = peaks,
                                            clipDurationMs = activeClip.durationMs,
                                        ),
                                    horizontalInsetFraction = 0f,
                                    loopRegionStartFraction = loopRegionStartFraction,
                                    loopRegionEndFraction = loopRegionEndFraction,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            TimelineLaneWaveformMode.LiveRecordingMeter ->
                                RecordingWaveform(
                                    inputLevel = recordingInputLevel ?: 0f,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            TimelineLaneWaveformMode.Status ->
                                when (activeClip.waveformState) {
                                    WaveformState.Loading ->
                                        WaveformStatusText("Generating...")
                                    WaveformState.Failed ->
                                        WaveformStatusText("No waveform")
                                    WaveformState.NoWaveform ->
                                        WaveformStatusText("No audio")
                                    is WaveformState.Ready -> Unit
                                }
                        }
                        if (showLocalPlayhead) {
                            TimelineLaneLocalPlayheadOverlay(
                                sourcePlayheadMs = localPlayheadSourceMs,
                                sourceDurationMs = activeClip.durationMs,
                                playheadLineWidthPx = playheadLineWidthPx,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (activeClip.isLoop) {
                        LoopRegionEditor(
                            sourceDurationMs = activeClip.durationMs,
                            loopStartMs = activeClip.effectiveStartMs,
                            loopEndMs = activeClip.effectiveEndMs,
                            editingEnabled = editingEnabled,
                            onLoopRegionCommit = { startMs, endMs ->
                                onLoopRegionCommit?.invoke(startMs, endMs)
                            },
                            waveformAreaWidthPx = waveformAreaWidthPx,
                            timelineScale = timelineScale,
                            sourceFitScale = sourceFitScale,
                            loopEditFocusActive = loopRegionEditFocus,
                            persistentViewportZoomed = laneViewportZoomed,
                            onLoopRegionEditFocusChanged = { loopRegionEditFocus = it },
                            onLoopRegionPreviewChanged = { startMs, endMs ->
                                loopPreviewStartMs = startMs
                                loopPreviewEndMs = endMs
                            },
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .zIndex(1f),
                        )
                    }
                }
                TimelineRuler(
                    timelineBaseDurationMs = laneScale.rulerDurationMs(),
                    clipStartFraction = laneScale.clipStartFractionOnWaveformArea(),
                    clipEndFraction = laneScale.rulerClipEndFraction(),
                    boundaryLabels =
                        if (laneScale.mode == TimelineLaneScaleMode.SourceFitWhileEditing) {
                            sourceFitRulerBoundaryLabels(
                                formattedTimelineStart =
                                    formatTimelineDuration(activeClip.startOffsetMs),
                                formattedTimelineEnd =
                                    formatTimelineDuration(
                                        timelineClipEndMs(
                                            activeClip.startOffsetMs,
                                            activeClip.durationMs,
                                        ),
                                    ),
                            )
                        } else {
                            timelineRulerBoundaryLabels(
                                clip = activeClip,
                                layout = layout,
                                timelineBaseDurationMs = laneLayoutDurationMs,
                            )
                        },
                    modifier = Modifier
                        .weight(TimelineRulerHeightFraction)
                        .fillMaxWidth(),
                )
            }
            if (drawGlobalPlayheadInLane) {
                TimelineLanePlayheadOverlay(
                    timeMs = playheadPositionMs,
                    timelineDurationMs = globalPlayheadTimelineDurationMs,
                    playheadLineWidthPx = playheadLineWidthPx,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        ClipMetadataArea(
            clip = activeClip,
            viewportZoomed = laneViewportZoomed,
            onViewportZoomedChanged = { laneViewportZoomed = it },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(TimelineMetadataWidthFraction)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun TimelineLanePlayheadOverlay(
    timeMs: Long,
    timelineDurationMs: Long,
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
            timelineMsToX(
                timeMs = timeMs,
                timelineDurationMs = timelineDurationMs,
                contentWidthPx = size.width,
            )
        drawLine(
            color = AppColors.Red,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = playheadLineWidthPx,
        )
    }
}

@Composable
private fun ClipMetadataArea(
    clip: TimelineClip,
    viewportZoomed: Boolean,
    onViewportZoomedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelStyle =
        TextStyle(
            color = Color.White,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
        )
    val channelLabel =
        if (clip.channelCount >= 2) {
            stringResource(R.string.track_channel_stereo)
        } else {
            stringResource(R.string.track_channel_mono)
        }
    val zoomedLabel = stringResource(R.string.track_lane_viewport_zoomed)

    Column(
        modifier = modifier
            .background(AppColors.Line)
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (clip.durationMs > 0L) {
            Text(
                text = zoomedLabel,
                style =
                    labelStyle.copy(
                        color =
                            if (viewportZoomed) {
                                Color.White
                            } else {
                                Color.White.copy(alpha = 0.45f)
                            },
                    ),
                modifier =
                    Modifier.clickable {
                        onViewportZoomedChanged(!viewportZoomed)
                    },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = channelLabel,
            style = labelStyle,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (clip.isTimelineBase) {
            Text(
                text = "BASE",
                style = labelStyle,
            )
        }
    }
}

@Composable
private fun BoxScope.WaveformStatusText(text: String) {
    Text(
        text = text,
        color = AppColors.Line.copy(alpha = 0.58f),
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = 6.dp, end = 6.dp),
    )
}
