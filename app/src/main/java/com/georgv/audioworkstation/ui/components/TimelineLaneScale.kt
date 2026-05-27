package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.core.track.pointerXToSourceMs
import kotlin.math.min

/**
 * How a loop lane maps source time to horizontal pixels inside the waveform container.
 * [Timeline] uses project placement (clip width on the shared timeline).
 * [SourceFitWhileEditing] expands the clip to the full waveform area while the lane is zoomed
 * (persistent toggle) or loop handles are dragged.
 */
enum class TimelineLaneScaleMode {
    Timeline,
    SourceFitWhileEditing,
}

data class TimelineLaneScale(
    val mode: TimelineLaneScaleMode,
    val sourceDurationMs: Long,
    val laneLayoutDurationMs: Long,
    val clipStartOffsetMs: Long,
    val clipDurationMs: Long,
)

fun timelineLaneUsesSourceFit(
    laneViewportZoomed: Boolean,
    loopRegionEditFocus: Boolean,
): Boolean = laneViewportZoomed || loopRegionEditFocus

fun timelineLaneScaleForLoopEdit(
    loopEditFocusActive: Boolean,
    laneLayoutDurationMs: Long,
    clip: TimelineClip,
): TimelineLaneScale {
    val mode =
        if (loopEditFocusActive) {
            TimelineLaneScaleMode.SourceFitWhileEditing
        } else {
            TimelineLaneScaleMode.Timeline
        }
    return TimelineLaneScale(
        mode = mode,
        sourceDurationMs = clip.durationMs.coerceAtLeast(1L),
        laneLayoutDurationMs = laneLayoutDurationMs.coerceAtLeast(1L),
        clipStartOffsetMs = clip.startOffsetMs.coerceAtLeast(0L),
        clipDurationMs = clip.durationMs.coerceAtLeast(1L),
    )
}

/** Clip box start as a fraction of the lane waveform area width. */
fun TimelineLaneScale.clipStartFractionOnWaveformArea(): Float =
    when (mode) {
        TimelineLaneScaleMode.Timeline ->
            (clipStartOffsetMs.toDouble() / laneLayoutDurationMs.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        TimelineLaneScaleMode.SourceFitWhileEditing -> 0f
    }

/** Clip box width as a fraction of the lane waveform area width. */
fun TimelineLaneScale.clipWidthFractionOnWaveformArea(): Float =
    when (mode) {
        TimelineLaneScaleMode.Timeline -> {
            val clipEndOnTimeline =
                min(clipStartOffsetMs + clipDurationMs, laneLayoutDurationMs)
            val visibleDuration = (clipEndOnTimeline - clipStartOffsetMs).coerceAtLeast(0L)
            (visibleDuration.toDouble() / laneLayoutDurationMs.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        }
        TimelineLaneScaleMode.SourceFitWhileEditing -> 1f
    }

/** Source-local ms → x inside the clip box (clip width always spans 0..[sourceDurationMs]). */
fun sourceMsToXInLaneClip(
    sourceMs: Long,
    clipWidthPx: Float,
    sourceDurationMs: Long,
): Float {
    if (clipWidthPx <= 0f || sourceDurationMs <= 0L) return 0f
    return clipWidthPx *
        (sourceMs.coerceIn(0L, sourceDurationMs).toDouble() / sourceDurationMs.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
}

fun xInLaneClipToSourceMs(
    xPx: Float,
    clipWidthPx: Float,
    sourceDurationMs: Long,
): Long = pointerXToSourceMs(xPx, clipWidthPx, sourceDurationMs)

fun TimelineLaneScale.waveformClipWidthPx(waveformAreaWidthPx: Float): Float =
    waveformAreaWidthPx * clipWidthFractionOnWaveformArea()

fun TimelineLaneScale.waveformClipStartPx(waveformAreaWidthPx: Float): Float =
    waveformAreaWidthPx * clipStartFractionOnWaveformArea()

/** Source ms → x in the full waveform column (not clip-local). */
fun sourceMsToPointerAreaX(
    sourceMs: Long,
    laneScale: TimelineLaneScale,
    waveformAreaWidthPx: Float,
): Float {
    val clipStartPx = laneScale.waveformClipStartPx(waveformAreaWidthPx)
    val clipWidthPx = laneScale.waveformClipWidthPx(waveformAreaWidthPx)
    return clipStartPx +
        sourceMsToXInLaneClip(
            sourceMs = sourceMs,
            clipWidthPx = clipWidthPx,
            sourceDurationMs = laneScale.sourceDurationMs,
        )
}

/** Waveform-column x → source ms. */
fun pointerAreaXToSourceMs(
    areaXPx: Float,
    laneScale: TimelineLaneScale,
    waveformAreaWidthPx: Float,
): Long {
    val clipStartPx = laneScale.waveformClipStartPx(waveformAreaWidthPx)
    val clipWidthPx = laneScale.waveformClipWidthPx(waveformAreaWidthPx)
    return xInLaneClipToSourceMs(
        xPx = areaXPx - clipStartPx,
        clipWidthPx = clipWidthPx,
        sourceDurationMs = laneScale.sourceDurationMs,
    )
}

/** Lane ruler duration: project timeline idle, source duration while loop edit-focused. */
fun TimelineLaneScale.rulerDurationMs(): Long =
    when (mode) {
        TimelineLaneScaleMode.Timeline -> laneLayoutDurationMs
        TimelineLaneScaleMode.SourceFitWhileEditing -> sourceDurationMs
    }

fun TimelineLaneScale.rulerClipEndFraction(): Float =
    (clipStartFractionOnWaveformArea() + clipWidthFractionOnWaveformArea()).coerceIn(0f, 1f)

data class LoopRegionOverlayAreaBounds(
    val startPx: Float,
    val endPx: Float,
)

fun loopRegionOverlayAreaBounds(
    loopStartMs: Long,
    loopEndMs: Long,
    displayScale: TimelineLaneScale,
    waveformAreaWidthPx: Float,
): LoopRegionOverlayAreaBounds {
    val startPx = sourceMsToPointerAreaX(loopStartMs, displayScale, waveformAreaWidthPx)
    val endPx = sourceMsToPointerAreaX(loopEndMs, displayScale, waveformAreaWidthPx)
    return LoopRegionOverlayAreaBounds(
        startPx = startPx.coerceIn(0f, waveformAreaWidthPx),
        endPx = endPx.coerceIn(0f, waveformAreaWidthPx),
    )
}

fun sourceFitRulerBoundaryLabels(
    formattedTimelineStart: String,
    formattedTimelineEnd: String,
): List<TimelineRulerBoundaryLabel> =
    listOf(
        TimelineRulerBoundaryLabel(
            text = formattedTimelineStart,
            fraction = 0f,
            alignToEnd = false,
        ),
        TimelineRulerBoundaryLabel(
            text = formattedTimelineEnd,
            fraction = 1f,
            alignToEnd = true,
        ),
    )
