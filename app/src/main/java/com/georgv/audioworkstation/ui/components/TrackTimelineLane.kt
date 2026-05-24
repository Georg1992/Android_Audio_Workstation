package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.data.db.entities.TrackEntity
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

fun projectTimelineClips(
    tracks: List<TrackEntity>,
    waveformStatesByTrackId: Map<String, WaveformState>,
): List<TimelineClip> {
    val playableTracks = tracks.mapNotNull { track ->
        val durationMs = track.duration?.takeIf { it > 0L } ?: return@mapNotNull null
        if (track.wavFilePath.isBlank() || track.isRecording) return@mapNotNull null
        val startOffsetMs = track.timelineStartOffsetMs.coerceAtLeast(0L)
        track to TimelineClipSpan(startOffsetMs = startOffsetMs, durationMs = durationMs.coerceAtLeast(0L))
    }
    if (playableTracks.isEmpty()) return emptyList()
    val baseEndMs =
        playableTracks.maxOf { (_, span) -> timelineClipEndMs(span.startOffsetMs, span.durationMs) }
    return playableTracks.map { (track, span) ->
        val clipEndMs = timelineClipEndMs(span.startOffsetMs, span.durationMs)
        TimelineClip(
            clipId = track.id,
            laneId = track.id,
            startOffsetMs = span.startOffsetMs,
            durationMs = span.durationMs,
            waveformState = waveformStatesByTrackId[track.id] ?: WaveformState.Loading,
            isTimelineBase = clipEndMs == baseEndMs,
            formattedDuration = formatTimelineDuration(span.durationMs),
            channelCount = track.channelCount.coerceIn(1, 2),
        )
    }
}

private data class TimelineClipSpan(
    val startOffsetMs: Long,
    val durationMs: Long,
)

fun timelineBaseDurationMs(clips: List<TimelineClip>): Long =
    clips.maxOfOrNull { timelineClipEndMs(it.startOffsetMs, it.durationMs) }
        ?.coerceAtLeast(TimelineMinimumBaseDurationMs)
        ?: TimelineMinimumBaseDurationMs

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
    timelineDurationMs: Long,
    playheadPositionMs: Long,
    recordingInputLevel: Float? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .heightIn(min = timelineLaneTotalHeightDp())
            .clip(shape)
            .background(AppColors.Bg)
            .border(Dimens.Stroke, AppColors.Line, shape)
    ) {
        val layout = clip?.let { timelineClipLayout(it, timelineDurationMs) } ?: return@BoxWithConstraints
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
                    val clipStart = maxWidth * layout.startFraction
                    val clipWidth =
                        (maxWidth * layout.widthFraction)
                            .coerceAtLeast(TimelineClipMinimumWidthDp.dp)
                    Box(
                        modifier = Modifier
                            .offset(x = clipStart)
                            .width(clipWidth)
                            .fillMaxHeight()
                            .clip(shape)
                            .background(AppColors.SurfacePanel)
                            .padding(top = Dimens.TightGap, start = 1.dp),
                    ) {
                        if (clip.isActiveRecording && recordingInputLevel != null) {
                            RecordingWaveform(
                                inputLevel = recordingInputLevel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            when (val waveform = clip.waveformState) {
                                WaveformState.Loading ->
                                    WaveformStatusText("Generating...")
                                WaveformState.Failed ->
                                    WaveformStatusText("No waveform")
                                WaveformState.NoWaveform ->
                                    WaveformStatusText("No audio")
                                is WaveformState.Ready ->
                                    TrackWaveform(
                                        peaks =
                                            waveformPeaksForTimelineClip(
                                                peaks = waveform.peaks,
                                                clipDurationMs = clip.durationMs,
                                            ),
                                        horizontalInsetFraction = 0f,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                            }
                        }
                    }
                }
                TimelineRuler(
                    timelineBaseDurationMs = timelineDurationMs,
                    clipStartFraction = layout.startFraction,
                    clipEndFraction = timelineClipEndFraction(layout),
                    boundaryLabels = timelineRulerBoundaryLabels(
                        clip = clip,
                        layout = layout,
                        timelineBaseDurationMs = timelineDurationMs,
                    ),
                    modifier = Modifier
                        .weight(TimelineRulerHeightFraction)
                        .fillMaxWidth(),
                )
            }
            TimelinePlayheadMarker(
                playheadPositionMs = playheadPositionMs,
                timelineDurationMs = timelineDurationMs,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ClipMetadataArea(
            clip = clip,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(TimelineMetadataWidthFraction)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun ClipMetadataArea(
    clip: TimelineClip,
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

    Column(
        modifier = modifier
            .background(AppColors.Line)
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
