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
import androidx.compose.runtime.SideEffect
import com.georgv.audioworkstation.ui.diagnostics.WaveformRecompositionDiagnostics
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.core.track.effectiveLoopEndMs
import com.georgv.audioworkstation.core.track.effectiveLoopStartMs
import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.core.track.hasTimelineClip
import com.georgv.audioworkstation.core.track.timelineClipDurationMs
import com.georgv.audioworkstation.core.track.trackLoopPlaybackPositionMs
import com.georgv.audioworkstation.core.track.trackLocalPlayheadVisibleInClip
import com.georgv.audioworkstation.core.track.trackSourcePlayheadMs
import com.georgv.audioworkstation.core.track.trackSourcePlayheadMsForClipTimelineWindow
import com.georgv.audioworkstation.core.audio.waveform.WaveformPeaks
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppOpacity
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
    data class Importing(val progress: Float) : WaveformState
    data object ImportFailed : WaveformState
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
    /** Non-destructive source trim start (ms from WAV start). */
    val sourceTrimStartMs: Long = 0L,
    val importStatus: TrackImportStatus = TrackImportStatus.READY,
    val importProgress: Float = 0f,
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

/** Per-lane timeline width for waveform/ruler layout (full clip extent, not mix selection). */
fun timelineLaneLocalLayoutDurationMs(clip: TimelineClip): Long {
    val endMs = timelineClipEffectiveTimelineEndMs(clip)
    if (endMs <= 0L) return 0L
    return endMs.coerceAtLeast(TimelineMinimumBaseDurationMs)
}

fun projectTimelineClips(
    tracks: List<TrackEntity>,
    waveformStatesByTrackId: Map<String, WaveformState>,
    importProgressByTrackId: Map<String, Float> = emptyMap(),
): List<TimelineClip> {
    val playableTracks = tracks.mapNotNull { track ->
        if (!track.hasTimelineClip()) return@mapNotNull null
        val durationMs = track.duration?.takeIf { it > 0L } ?: return@mapNotNull null
        if (track.isRecording && track.importStatus == TrackImportStatus.READY && track.wavFilePath.isBlank()) {
            return@mapNotNull null
        }
        val startOffsetMs = track.timelineStartOffsetMs.coerceAtLeast(0L)
        val visibleDurationMs = track.timelineClipDurationMs().coerceAtLeast(0L)
        track to TimelineClipSpan(startOffsetMs = startOffsetMs, durationMs = visibleDurationMs)
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
            sourceTrimStartMs = track.trimStartMs.coerceAtLeast(0L),
            importStatus = track.importStatus,
            importProgress = importProgressByTrackId[track.id] ?: 0f,
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
fun waveformPeaksForTrimmedClip(
    peaks: WaveformPeaks,
    trimStartMs: Long,
    clipDurationMs: Long,
): WaveformPeaks {
    if (clipDurationMs <= 0L) return peaks
    if (trimStartMs <= 0L) return waveformPeaksForTimelineClip(peaks, clipDurationMs)
    val sourceDurationMs = peaks.sourceDurationMs
    if (sourceDurationMs <= 0L) return peaks
    val trimEndMs = (trimStartMs + clipDurationMs).coerceAtMost(sourceDurationMs)
    return waveformPeaksForLoopPlaybackRegion(
        peaks = peaks,
        loopStartMs = trimStartMs,
        loopEndMs = trimEndMs,
        clipDurationMs = clipDurationMs,
    )
}

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

/** Slice [peaks] to the loop region so it can span the full waveform width during loop playback. */
fun waveformPeaksForLoopPlaybackRegion(
    peaks: WaveformPeaks,
    loopStartMs: Long,
    loopEndMs: Long,
    clipDurationMs: Long,
): WaveformPeaks {
    if (clipDurationMs <= 0L) return peaks
    val sourceDurationMs = peaks.sourceDurationMs.coerceAtLeast(clipDurationMs)
    val startMs = loopStartMs.coerceIn(0L, clipDurationMs)
    val endMs = loopEndMs.coerceIn(startMs + 1L, clipDurationMs)
    val regionDurationMs = (endMs - startMs).coerceAtLeast(1L)

    fun sliceRange(totalBars: Int): IntRange? {
        if (totalBars <= 0) return null
        val startBar =
            ((totalBars.toLong() * startMs) / sourceDurationMs)
                .toInt()
                .coerceIn(0, totalBars - 1)
        val endBarExclusive =
            ((totalBars.toLong() * endMs + sourceDurationMs - 1L) / sourceDurationMs)
                .toInt()
                .coerceIn(startBar + 1, totalBars)
        return startBar until endBarExclusive
    }

    if (peaks.isStereo) {
        val left = peaks.leftAmplitudes.orEmpty()
        val right = peaks.rightAmplitudes.orEmpty()
        val range = sliceRange(minOf(left.size, right.size)) ?: return peaks
        return peaks.copy(
            leftAmplitudes = left.slice(range),
            rightAmplitudes = right.slice(range),
            sourceDurationMs = regionDurationMs,
        )
    }

    val range = sliceRange(peaks.amplitudes.size) ?: return peaks
    return peaks.copy(
        amplitudes = peaks.amplitudes.slice(range),
        sourceDurationMs = regionDurationMs,
    )
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
    laneLayoutDurationMs: Long,
    globalMixScopeDurationMs: Long = laneLayoutDurationMs,
): List<TimelineRulerBoundaryLabel> {
    val startMs = clip.startOffsetMs.coerceAtLeast(0L)
    val clipEndMs = timelineClipEndTimeMs(clip, laneLayoutDurationMs)
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

    if (globalMixScopeDurationMs <= 0L || globalMixScopeDurationMs >= laneLayoutDurationMs) {
        return labels
    }

    return labels +
        TimelineRulerBoundaryLabel(
            text = formatTimelineDuration(globalMixScopeDurationMs),
            fraction =
                (globalMixScopeDurationMs.toDouble() / laneLayoutDurationMs.toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f),
            alignToEnd = true,
        )
}

/**
 * Live recording lanes grow with [playheadPositionMs] without rebuilding the shared clips map.
 */
internal fun timelineClipAndLaneDurationForLayout(
    clip: TimelineClip,
    laneLayoutDurationMs: Long,
    playheadPositionMs: Long,
): Pair<TimelineClip, Long> {
    if (!clip.isActiveRecording) {
        return clip to laneLayoutDurationMs
    }
    val elapsedMs = (playheadPositionMs - clip.startOffsetMs).coerceAtLeast(0L)
    val durationMs = layoutDurationMsForActiveRecording(elapsedMs)
    val layoutClip =
        clip.copy(
            durationMs = durationMs,
            formattedDuration = formatTimelineDuration(elapsedMs),
        )
    val laneEndMs = clip.startOffsetMs + durationMs
    val effectiveLaneDurationMs =
        max(laneLayoutDurationMs, laneEndMs).coerceAtLeast(TimelineMinimumBaseDurationMs)
    return layoutClip to effectiveLaneDurationMs
}

@Composable
fun TrackTimelineLane(
    clip: TimelineClip?,
    /** Lane-local layout width (full clip extent on the project timeline). */
    laneLayoutDurationMs: Long,
    /** Global project timeline for non-loop lane playhead alignment. */
    globalPlayheadTimelineDurationMs: Long,
    /** Selection-scoped mix end for optional ruler marker on longer unselected lanes. */
    globalMixScopeDurationMs: Long = laneLayoutDurationMs,
    playheadPositionMs: Long,
    loopPlaybackActive: Boolean = false,
    recordingInputLevel: Float? = null,
    loopRegionEditingEnabled: Boolean = false,
    onLoopRegionCommit: ((loopStartMs: Long, loopEndMs: Long) -> Unit)? = null,
    /** Loop waveform container bounds for reorder exclusion (wave area only, not ruler/metadata). */
    onLoopWaveformContainerBoundsInRoot: ((Rect) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)
    val activeWaveformState = clip?.waveformState
    SideEffect {
        if (clip != null && activeWaveformState != null) {
            WaveformRecompositionDiagnostics.logTrackTimelineLaneRecomposition(
                trackId = clip.clipId,
                waveformState = activeWaveformState,
            )
        }
    }
    val density = LocalDensity.current
    val playheadLineWidthPx = with(density) { 1.dp.toPx() }
    val (layoutClip, effectiveLaneLayoutDurationMs) =
        clip?.let { timelineClipAndLaneDurationForLayout(it, laneLayoutDurationMs, playheadPositionMs) }
            ?: (null to laneLayoutDurationMs)
    val laneLayout = layoutClip?.let { timelineClipLayout(it, effectiveLaneLayoutDurationMs) }
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
        val activeClip = layoutClip ?: return@BoxWithConstraints
        val layout = laneLayout
        if (layout == null) {
            ImportTimelineLaneFallback(
                clip = activeClip,
                recordingInputLevel = recordingInputLevel,
                modifier = Modifier.fillMaxSize(),
            )
            return@BoxWithConstraints
        }
        var loopRegionEditFocus by remember(activeClip.laneId) { mutableStateOf(false) }
        var laneViewportZoomed by remember(activeClip.laneId) { mutableStateOf(false) }
        val timelineScale =
            remember(effectiveLaneLayoutDurationMs, activeClip.laneId, activeClip.durationMs, activeClip.startOffsetMs) {
                timelineLaneScaleForLoopEdit(
                    loopEditFocusActive = false,
                    laneLayoutDurationMs = effectiveLaneLayoutDurationMs,
                    clip = activeClip,
                )
            }
        val loopPlaybackScale =
            remember(
                effectiveLaneLayoutDurationMs,
                activeClip.laneId,
                activeClip.effectiveStartMs,
                activeClip.effectiveEndMs,
            ) {
                timelineLaneScaleForLoopPlayback(
                    laneLayoutDurationMs = effectiveLaneLayoutDurationMs,
                    clip = activeClip,
                    loopStartMs = activeClip.effectiveStartMs,
                    loopEndMs = activeClip.effectiveEndMs,
                )
            }
        val sourceFitScale =
            remember(effectiveLaneLayoutDurationMs, activeClip.laneId, activeClip.durationMs, activeClip.startOffsetMs) {
                timelineLaneScaleForLoopEdit(
                    loopEditFocusActive = true,
                    laneLayoutDurationMs = effectiveLaneLayoutDurationMs,
                    clip = activeClip,
                )
            }
        val useLoopPlaybackProjection = activeClip.isLoop && loopPlaybackActive
        val lanePlayheadMs =
            if (useLoopPlaybackProjection) {
                trackLoopPlaybackPositionMs(
                    rawPlayheadMs = playheadPositionMs,
                    loopStartMs = activeClip.effectiveStartMs,
                    loopEndMs = activeClip.effectiveEndMs,
                )
            } else {
                playheadPositionMs
            }
        val laneScale =
            when {
                useLoopPlaybackProjection -> loopPlaybackScale
                timelineLaneUsesSourceFit(laneViewportZoomed, loopRegionEditFocus) -> sourceFitScale
                else -> timelineScale
            }
        val drawGlobalPlayheadInLane = !activeClip.isLoop && !laneViewportZoomed && !useLoopPlaybackProjection
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
                        .fillMaxWidth()
                        .then(
                            if (activeClip.isLoop && onLoopWaveformContainerBoundsInRoot != null) {
                                Modifier.onGloballyPositioned { coordinates ->
                                    onLoopWaveformContainerBoundsInRoot.invoke(
                                        coordinates.boundsInRoot(),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
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
                            globalPlayheadMs = lanePlayheadMs,
                            timelineStartOffsetMs = activeClip.startOffsetMs,
                            sourceDurationMs = activeClip.durationMs,
                            loopEnabled = activeClip.isLoop,
                            loopStartMs = activeClip.effectiveStartMs,
                            loopEndMs = activeClip.effectiveEndMs,
                            loopPlaybackActive = useLoopPlaybackProjection,
                        )
                    val clipLocalPlayheadMs =
                        if (laneViewportZoomed) {
                            trackSourcePlayheadMsForClipTimelineWindow(
                                globalPlayheadMs = lanePlayheadMs,
                                timelineStartOffsetMs = activeClip.startOffsetMs,
                                sourceDurationMs = activeClip.durationMs,
                                loopEnabled = activeClip.isLoop,
                                loopStartMs = activeClip.effectiveStartMs,
                                loopEndMs = activeClip.effectiveEndMs,
                                loopPlaybackActive = useLoopPlaybackProjection,
                            )
                        } else {
                            null
                        }
                    val showLocalPlayhead =
                        when {
                            useLoopPlaybackProjection -> true
                            laneViewportZoomed -> clipLocalPlayheadMs != null
                            activeClip.isLoop ->
                                trackLocalPlayheadVisibleInClip(
                                    globalPlayheadMs = lanePlayheadMs,
                                    timelineStartOffsetMs = activeClip.startOffsetMs,
                                    clipDurationMs = activeClip.durationMs,
                                    loopEnabled = true,
                                )
                            else -> false
                        }
                    val localPlayheadSourceMs = clipLocalPlayheadMs ?: sourcePlayheadMs
                    val clipImporting = activeClip.importStatus == TrackImportStatus.IMPORTING
                    val clipImportFailed = activeClip.importStatus == TrackImportStatus.FAILED
                    val clipBorderColor =
                        when {
                            clipImporting -> AppColors.Cyan
                            clipImportFailed -> AppColors.Red
                            else -> Color.Transparent
                        }
                    val clipBorderWidth = if (clipImporting || clipImportFailed) 2.dp else 0.dp
                    Box(
                        modifier = Modifier
                            .offset(x = clipStart)
                            .width(clipWidth)
                            .fillMaxHeight()
                            .clip(shape)
                            .background(
                                if (clipImporting) {
                                    AppColors.SurfacePanel.copy(alpha = 0.55f)
                                } else if (clipImportFailed) {
                                    AppColors.SurfacePanel.copy(alpha = AppOpacity.muted)
                                } else {
                                    AppColors.SurfacePanel
                                },
                            )
                            .border(clipBorderWidth, clipBorderColor, shape)
                            .padding(top = Dimens.TightGap, start = 1.dp),
                    ) {
                        when (
                            timelineLaneWaveformMode(
                                waveformState = activeClip.waveformState,
                                importStatus = activeClip.importStatus,
                                isActiveRecording = activeClip.isActiveRecording,
                                recordingInputLevel = recordingInputLevel,
                            )
                        ) {
                            TimelineLaneWaveformMode.PersistedPeaks -> {
                                val peaks = (activeClip.waveformState as WaveformState.Ready).peaks
                                val displayPeaks =
                                    if (useLoopPlaybackProjection) {
                                        waveformPeaksForLoopPlaybackRegion(
                                            peaks = peaks,
                                            loopStartMs = activeClip.effectiveStartMs,
                                            loopEndMs = activeClip.effectiveEndMs,
                                            clipDurationMs = activeClip.durationMs,
                                        )
                                    } else {
                                        waveformPeaksForTrimmedClip(
                                            peaks = peaks,
                                            trimStartMs = activeClip.sourceTrimStartMs,
                                            clipDurationMs = activeClip.durationMs,
                                        )
                                    }
                                val loopRegionStartFraction =
                                    if (activeClip.isLoop && activeClip.durationMs > 0L && !useLoopPlaybackProjection) {
                                        loopPreviewStartMs.toFloat() / activeClip.durationMs.toFloat()
                                    } else {
                                        null
                                    }
                                val loopRegionEndFraction =
                                    if (activeClip.isLoop && activeClip.durationMs > 0L && !useLoopPlaybackProjection) {
                                        loopPreviewEndMs.toFloat() / activeClip.durationMs.toFloat()
                                    } else {
                                        null
                                    }
                                TrackWaveform(
                                    peaks =
                                        displayPeaks,
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
                            TimelineLaneWaveformMode.Importing -> {
                                val importingState = activeClip.waveformState as? WaveformState.Importing
                                ImportingWaveform(
                                    progress = importingState?.progress ?: activeClip.importProgress,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            TimelineLaneWaveformMode.Status ->
                                when (activeClip.waveformState) {
                                    WaveformState.Loading ->
                                        WaveformLanePlaceholder(
                                            stringResource(R.string.waveform_generating),
                                        )
                                    WaveformState.Failed ->
                                        WaveformLanePlaceholder(
                                            stringResource(R.string.waveform_unavailable),
                                        )
                                    WaveformState.ImportFailed ->
                                        WaveformLanePlaceholder(
                                            stringResource(R.string.import_clip_failed),
                                        )
                                    WaveformState.NoWaveform -> WaveformLanePlaceholder()
                                    is WaveformState.Ready -> Unit
                                    is WaveformState.Importing -> Unit
                                }
                        }
                        if (showLocalPlayhead) {
                            TimelineLaneLocalPlayheadOverlay(
                                sourcePlayheadMs = localPlayheadSourceMs,
                                sourceDurationMs =
                                    if (useLoopPlaybackProjection) {
                                        (activeClip.effectiveEndMs - activeClip.effectiveStartMs)
                                            .coerceAtLeast(1L)
                                    } else {
                                        activeClip.durationMs
                                    },
                                loopPlaybackProjection =
                                    if (useLoopPlaybackProjection) {
                                        TimelineLaneLocalPlayheadLoopProjection(
                                            loopStartMs = activeClip.effectiveStartMs,
                                            loopEndMs = activeClip.effectiveEndMs,
                                        )
                                    } else {
                                        null
                                    },
                                playheadLineWidthPx = playheadLineWidthPx,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (activeClip.isLoop && !useLoopPlaybackProjection) {
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
                        when (laneScale.mode) {
                            TimelineLaneScaleMode.SourceFitWhileEditing ->
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
                            TimelineLaneScaleMode.LoopPlayback ->
                                loopPlaybackRulerBoundaryLabels(
                                    loopStartMs = activeClip.effectiveStartMs,
                                    loopEndMs = activeClip.effectiveEndMs,
                                )
                            TimelineLaneScaleMode.Timeline ->
                                timelineRulerBoundaryLabels(
                                    clip = activeClip,
                                    layout = layout,
                                    laneLayoutDurationMs = laneLayoutDurationMs,
                                    globalMixScopeDurationMs = globalMixScopeDurationMs,
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
private fun ImportTimelineLaneFallback(
    clip: TimelineClip,
    recordingInputLevel: Float?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(AppColors.SurfacePanel)
                .border(
                    width = 2.dp,
                    color =
                        when (clip.importStatus) {
                            TrackImportStatus.IMPORTING -> AppColors.Cyan
                            TrackImportStatus.FAILED -> AppColors.Red
                            else -> AppColors.Line
                        },
                    shape = shape,
                )
                .padding(Dimens.TightGap),
    ) {
        when (
            timelineLaneWaveformMode(
                waveformState = clip.waveformState,
                importStatus = clip.importStatus,
                isActiveRecording = clip.isActiveRecording,
                recordingInputLevel = recordingInputLevel,
            )
        ) {
            TimelineLaneWaveformMode.Importing -> {
                val importingState = clip.waveformState as? WaveformState.Importing
                ImportingWaveform(
                    progress = importingState?.progress ?: clip.importProgress,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            TimelineLaneWaveformMode.Status ->
                when (clip.waveformState) {
                    WaveformState.ImportFailed ->
                        WaveformLanePlaceholder(stringResource(R.string.import_clip_failed))
                    WaveformState.Loading ->
                        WaveformLanePlaceholder(stringResource(R.string.waveform_generating))
                    else -> WaveformLanePlaceholder()
                }
            else -> Unit
        }
    }
}

@Composable
private fun BoxScope.WaveformLanePlaceholder(statusText: String? = null) {
    if (statusText != null) {
        Text(
            text = statusText,
            color = AppColors.textSecondary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 6.dp, end = 6.dp),
        )
    }
}
