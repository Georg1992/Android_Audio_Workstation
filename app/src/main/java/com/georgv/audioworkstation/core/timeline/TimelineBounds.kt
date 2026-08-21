package com.georgv.audioworkstation.core.timeline

import kotlin.math.roundToLong

/** Viewport/ruler sanity cap for playback scrub and layout — not a recording duration limit. */
const val TimelineMaxDurationMs = 10 * 60 * 1000L

/** Smallest non-zero base/visible timeline length used when a clip exists. */
const val TimelineMinimumBaseDurationMs = 1L

/** Offline mixdown always renders from timeline 0:00. */
const val MixdownTimelineStartMs = 0L

fun timelinePlayheadFraction(positionMs: Long, timelineDurationMs: Long): Float {
    if (timelineDurationMs <= 0L) return 0f
    return (positionMs.toFloat() / timelineDurationMs.toFloat()).coerceIn(0f, 1f)
}

fun timelinePlayheadPositionMs(fraction: Float, timelineDurationMs: Long): Long {
    if (timelineDurationMs <= 0L) return 0L
    return (fraction.coerceIn(0f, 1f) * timelineDurationMs.toFloat()).roundToLong()
}

fun timelinePlayheadClampedPositionMs(positionMs: Long, timelineDurationMs: Long): Long =
    timelinePlayheadPositionMs(
        timelinePlayheadFraction(positionMs, timelineDurationMs),
        timelineDurationMs,
    )
