package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.ui.components.TimelineMinimumBaseDurationMs

/**
 * Timeline duration for the lane-level global playhead overlay (non-loop, timeline mode).
 * Matches the top scrubber: base timeline until extended by recording or loop playback.
 */
fun trackLaneGlobalOverlayTimelineDurationMs(
    laneLayoutDurationMs: Long,
    rawPlayheadMs: Long,
    timelineVisibleDurationMs: Long,
): Long =
    maxOf(
        laneLayoutDurationMs.coerceAtLeast(0L),
        timelineVisibleDurationMs.coerceAtLeast(0L),
        rawPlayheadMs.coerceAtLeast(0L),
    ).coerceAtLeast(TimelineMinimumBaseDurationMs)

/** Source-local playhead clamped to the active loop region and clip duration. */
fun coerceValidLoopIdleSourcePlayheadMs(
    sourceMs: Long,
    loopStartMs: Long,
    loopEndMs: Long,
    sourceDurationMs: Long,
): Long {
    if (sourceDurationMs <= 0L) return 0L
    val start = loopStartMs.coerceIn(0L, sourceDurationMs)
    val end = loopEndMs.coerceIn(start + 1L, sourceDurationMs)
    return sourceMs.coerceIn(start, end)
}
