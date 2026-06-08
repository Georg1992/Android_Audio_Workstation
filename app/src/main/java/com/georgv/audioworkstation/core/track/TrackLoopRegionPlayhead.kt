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
    loopPlaybackActive: Boolean = false,
    loopStartMs: Long = 0L,
    loopEndMs: Long = 0L,
): Boolean {
    if (clipDurationMs <= 0L) return false
    if (loopEnabled && loopPlaybackActive) {
        val local =
            loopPlaybackClipLocalSourceMs(
                loopPlaybackPositionMs = globalPlayheadMs,
                clipDurationMs = clipDurationMs,
                loopStartMs = loopStartMs,
                loopEndMs = loopEndMs,
            )
        return local in loopStartMs..clipDurationMs.coerceAtLeast(0L)
    }
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
    loopPlaybackActive: Boolean = false,
): Long? {
    if (
        !trackLocalPlayheadVisibleInClip(
            globalPlayheadMs = globalPlayheadMs,
            timelineStartOffsetMs = timelineStartOffsetMs,
            clipDurationMs = sourceDurationMs,
            loopEnabled = loopEnabled,
            limitToClipTimelineWindow = !loopEnabled,
            loopPlaybackActive = loopPlaybackActive,
            loopStartMs = loopStartMs,
            loopEndMs = loopEndMs,
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
        loopPlaybackActive = loopPlaybackActive,
    )
}

/**
 * Source-local playhead for waveform display.
 * Non-loop: timeline-local ms clamped to clip. Loop playback: loop-projected source ms.
 */
fun trackSourcePlayheadMs(
    globalPlayheadMs: Long,
    timelineStartOffsetMs: Long,
    sourceDurationMs: Long,
    loopEnabled: Boolean,
    loopStartMs: Long,
    loopEndMs: Long,
    loopPlaybackActive: Boolean = false,
): Long {
    if (!loopEnabled) {
        return clipLocalPlayheadMs(
            globalPlayheadMs = globalPlayheadMs,
            clipStartOffsetMs = timelineStartOffsetMs,
            clipDurationMs = sourceDurationMs,
        )
    }
    if (loopPlaybackActive) {
        return loopPlaybackClipLocalSourceMs(
            loopPlaybackPositionMs = globalPlayheadMs,
            clipDurationMs = sourceDurationMs,
            loopStartMs = loopStartMs,
            loopEndMs = loopEndMs,
        )
    }
    val local = trackLocalTimelineMs(globalPlayheadMs, timelineStartOffsetMs)
    if (local < 0L) {
        return coerceValidLoopIdleSourcePlayheadMs(
            sourceMs = loopStartMs,
            loopStartMs = loopStartMs,
            loopEndMs = loopEndMs,
            sourceDurationMs = sourceDurationMs,
        )
    }
    val loopLength = trackLoopLengthMs(loopStartMs, loopEndMs)
    val wrapped = loopStartMs + (local % loopLength)
    return coerceValidLoopIdleSourcePlayheadMs(
        sourceMs = wrapped,
        loopStartMs = loopStartMs,
        loopEndMs = loopEndMs,
        sourceDurationMs = sourceDurationMs,
    )
}
