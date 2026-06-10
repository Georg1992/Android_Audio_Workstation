package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.data.db.entities.TrackEntity

/** Loop region length (ms) for a track's active loop bounds. */
fun trackLoopLengthMs(loopStartMs: Long, loopEndMs: Long): Long =
    (loopEndMs - loopStartMs).coerceAtLeast(1L)

/**
 * Longest loop region among loop-enabled tracks in [sessionTrackIds].
 * Used as the shared global loop timeline extent during loop playback.
 */
fun sessionLoopTimelineDurationMs(
    tracks: List<TrackEntity>,
    sessionTrackIds: Set<String>,
): Long {
    if (sessionTrackIds.isEmpty()) return 0L
    return tracks
        .asSequence()
        .filter { it.id in sessionTrackIds && it.isLoop }
        .map { track ->
            trackLoopLengthMs(
                loopStartMs = track.effectiveLoopStartMs(),
                loopEndMs = track.effectiveLoopEndMs(),
            )
        }
        .maxOrNull() ?: 0L
}

/** Loop playback position wrapped to [0, loopLength) for this track's loop region. */
fun trackLoopPlaybackPositionMs(
    rawPlayheadMs: Long,
    loopStartMs: Long,
    loopEndMs: Long,
): Long {
    val loopLength = trackLoopLengthMs(loopStartMs, loopEndMs)
    return ((rawPlayheadMs % loopLength) + loopLength) % loopLength
}

/**
 * Non-loop clip-local playhead on the project timeline (ms from clip start on timeline).
 * [globalPlayheadMs] is absolute project transport ms.
 */
fun clipLocalPlayheadMs(
    globalPlayheadMs: Long,
    clipStartOffsetMs: Long,
    clipDurationMs: Long,
): Long {
    val local = globalPlayheadMs - clipStartOffsetMs.coerceAtLeast(0L)
    return local.coerceIn(0L, clipDurationMs.coerceAtLeast(0L))
}

/**
 * Source position visible at [loopPlaybackPositionMs] inside [loopStartMs, loopEndMs).
 * [loopPlaybackPositionMs] is the engine/global loop playback position (0 at loop start).
 */
fun loopVisibleSourceMs(
    loopPlaybackPositionMs: Long,
    loopStartMs: Long,
    loopEndMs: Long,
): Long {
    val loopLength = trackLoopLengthMs(loopStartMs, loopEndMs)
    val wrapped = ((loopPlaybackPositionMs % loopLength) + loopLength) % loopLength
    return loopStartMs + wrapped
}

/**
 * Loop-projected clip-local source ms from the shared loop playback position.
 *
 * visibleSourceMs = loopStartMs + loopPlaybackPositionMs (wrapped to loop range)
 * localPlayheadMs = visibleSourceMs when [clipStartOffsetMs] is 0 on the source axis; callers pass
 * timeline [clipStartOffsetMs] only for non-loop mapping. During loop playback projection the clip
 * box shows the loop region only, so local ms is source-local within the clip file.
 */
fun loopPlaybackClipLocalSourceMs(
    loopPlaybackPositionMs: Long,
    clipDurationMs: Long,
    loopStartMs: Long,
    loopEndMs: Long,
): Long {
    val visibleSourceMs = loopVisibleSourceMs(loopPlaybackPositionMs, loopStartMs, loopEndMs)
    return visibleSourceMs.coerceIn(0L, clipDurationMs.coerceAtLeast(0L))
}

/** Source ms → x inside a loop-playback waveform box (loop range spans full width). */
fun loopPlaybackSourceMsToXInClip(
    sourceMs: Long,
    clipWidthPx: Float,
    loopStartMs: Long,
    loopEndMs: Long,
): Float {
    if (clipWidthPx <= 0f) return 0f
    val loopLength = trackLoopLengthMs(loopStartMs, loopEndMs)
    val offsetInLoop = (sourceMs - loopStartMs).coerceIn(0L, loopLength)
    return clipWidthPx *
        (offsetInLoop.toDouble() / loopLength.toDouble()).toFloat().coerceIn(0f, 1f)
}
