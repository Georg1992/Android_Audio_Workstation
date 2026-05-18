package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlin.math.max
import kotlin.math.min

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

fun projectTimelineClips(
    tracks: List<TrackEntity>,
    waveformStatesByTrackId: Map<String, WaveformState>,
): List<TimelineClip> {
    val playableTracks = tracks.mapNotNull { track ->
        val durationMs = track.duration?.takeIf { it > 0L } ?: return@mapNotNull null
        if (track.wavFilePath.isBlank() || track.isRecording) return@mapNotNull null
        track to durationMs.coerceAtMost(TimelineMaxDurationMs)
    }
    val baseDurationMs = playableTracks.maxOfOrNull { (_, durationMs) -> durationMs } ?: return emptyList()
    return playableTracks.map { (track, durationMs) ->
        TimelineClip(
            clipId = track.id,
            laneId = track.id,
            startOffsetMs = 0L,
            durationMs = durationMs,
            waveformState = waveformStatesByTrackId[track.id] ?: WaveformState.Loading,
            isTimelineBase = durationMs == baseDurationMs,
            formattedDuration = formatTimelineDuration(durationMs),
        )
    }
}

fun timelineBaseDurationMs(clips: List<TimelineClip>): Long =
    clips.maxOfOrNull { it.durationMs.coerceIn(0L, TimelineMaxDurationMs) }
        ?.coerceAtLeast(TimelineMinimumBaseDurationMs)
        ?: TimelineMinimumBaseDurationMs

fun timelineClipLayout(
    clip: TimelineClip,
    timelineBaseDurationMs: Long,
): TimelineClipLayout? {
    if (clip.durationMs <= 0L || timelineBaseDurationMs <= 0L) return null
    val start = clip.startOffsetMs.coerceIn(0L, TimelineMaxDurationMs)
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

@Composable
fun TrackTimelineLane(
    clip: TimelineClip?,
    timelineBaseDurationMs: Long,
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
        val layout = clip?.let { timelineClipLayout(it, timelineBaseDurationMs) } ?: return@BoxWithConstraints
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(TimelineWaveformWidthFraction)
                .fillMaxHeight(),
        ) {
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
                ) {
                    when (val waveform = clip.waveformState) {
                        WaveformState.Loading ->
                            WaveformStatusText("Generating...")
                        WaveformState.Failed ->
                            WaveformStatusText("No waveform")
                        WaveformState.NoWaveform ->
                            WaveformStatusText("No audio")
                        is WaveformState.Ready ->
                            TrackWaveform(
                                peaks = waveform.peaks,
                                horizontalInsetFraction = 0f,
                                modifier = Modifier.fillMaxSize(),
                            )
                    }
                }
            }
            TimelineRuler(
                timelineBaseDurationMs = timelineBaseDurationMs,
                clipStartFraction = layout.startFraction,
                clipEndFraction = timelineClipEndFraction(layout),
                clipStartTimeLabel = formatTimelineDuration(0L),
                clipEndTimeLabel = formatTimelineDuration(clip.durationMs),
                modifier = Modifier
                    .weight(TimelineRulerHeightFraction)
                    .fillMaxWidth(),
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
    Box(
        modifier = modifier
            .background(AppColors.Line)
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        if (clip.isTimelineBase) {
            Text(
                text = "BASE",
                color = Color.White,
                fontSize = 7.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.Center),
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
