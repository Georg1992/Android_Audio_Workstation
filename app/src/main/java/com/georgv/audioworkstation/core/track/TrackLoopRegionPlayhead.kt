package com.georgv.audioworkstation.core.track

/** Track-local timeline position from the shared global playhead (ms). */
fun trackLocalTimelineMs(globalPlayheadMs: Long, timelineStartOffsetMs: Long): Long =
    globalPlayheadMs - timelineStartOffsetMs.coerceAtLeast(0L)

/**
 * Whether the lane-local playhead should draw inside this clip.
 *
 * Looped clips stay visible once playback reaches the clip start, even when the global
 * playhead moves past [clipDurationMs] or the base timeline (source position wraps).
 */
fun trackLocalPlayheadVisibleInClip(
    globalPlayheadMs: Long,
    timelineStartOffsetMs: Long,
    clipDurationMs: Long,
    loopEnabled: Boolean = false,
    limitToClipTimelineWindow: Boolean = false,
): Boolean {
    if (clipDurationMs <= 0L) return false
    val local = trackLocalTimelineMs(globalPlayheadMs, timelineStartOffsetMs)
    if (local < 0L) return false
    if (loopEnabled) return true
    if (limitToClipTimelineWindow) {
        return local <= clipDurationMs
    }
    return true
}

/** Source-local playhead x inside a source-fit (zoomed) clip from the global playhead. */
fun trackSourcePlayheadMsForClipTimelineWindow(
    globalPlayheadMs: Long,
    timelineStartOffsetMs: Long,
    sourceDurationMs: Long,
    loopEnabled: Boolean,
    loopStartMs: Long,
    loopEndMs: Long,
): Long? {
    if (
        !trackLocalPlayheadVisibleInClip(
            globalPlayheadMs = globalPlayheadMs,
            timelineStartOffsetMs = timelineStartOffsetMs,
            clipDurationMs = sourceDurationMs,
            loopEnabled = loopEnabled,
            limitToClipTimelineWindow = !loopEnabled,
        )
    ) {
        return null
    }
    return trackSourcePlayheadMs(
        globalPlayheadMs = globalPlayheadMs,
        timelineStartOffsetMs = timelineStartOffsetMs,
        sourceDurationMs = sourceDurationMs,
        loopEnabled = loopEnabled,
        loopStartMs = loopStartMs,
        loopEndMs = loopEndMs,
    )
}

/**
 * Source-local playhead for waveform display and loop wrap semantics.
 * Non-loop: track-local timeline ms. Loop: wraps inside [loopStartMs, loopEndMs).
 */
fun trackSourcePlayheadMs(
    globalPlayheadMs: Long,
    timelineStartOffsetMs: Long,
    sourceDurationMs: Long,
    loopEnabled: Boolean,
    loopStartMs: Long,
    loopEndMs: Long,
): Long {
    val local = trackLocalTimelineMs(globalPlayheadMs, timelineStartOffsetMs)
    if (!loopEnabled) return local.coerceIn(0L, sourceDurationMs.coerceAtLeast(0L))
    if (local < 0L) return loopStartMs.coerceAtLeast(0L)
    val loopLength = (loopEndMs - loopStartMs).coerceAtLeast(1L)
    return loopStartMs + (local % loopLength)
}
