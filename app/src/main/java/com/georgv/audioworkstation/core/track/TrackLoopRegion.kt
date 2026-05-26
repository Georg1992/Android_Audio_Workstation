package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.data.db.entities.TrackEntity

/** Minimum selectable loop region length on the timeline (ms). */
const val TrackLoopRegionMinLengthMs = 100L

fun TrackEntity.sourceDurationMs(): Long = duration?.coerceAtLeast(0L) ?: 0L

/** Existing on-disk audio that can be shown on the timeline (including punch-record targets). */
fun TrackEntity.hasPersistedPlayableAudio(): Boolean =
    wavFilePath.isNotBlank() && sourceDurationMs() > 0L

/** Overlay/drag display bounds: preview while dragging, hold commit until props match, else persisted. */
fun loopRegionDisplayBoundsMs(
    isDragging: Boolean,
    previewStartMs: Long,
    previewEndMs: Long,
    pendingCommitStartMs: Long?,
    pendingCommitEndMs: Long?,
    loopStartMs: Long,
    loopEndMs: Long,
): Pair<Long, Long> =
    when {
        isDragging -> previewStartMs to previewEndMs
        pendingCommitStartMs != null && pendingCommitEndMs != null ->
            pendingCommitStartMs to pendingCommitEndMs
        else -> loopStartMs to loopEndMs
    }

fun loopRegionPendingCommitResolved(
    loopStartMs: Long,
    loopEndMs: Long,
    pendingCommitStartMs: Long?,
    pendingCommitEndMs: Long?,
): Boolean =
    pendingCommitStartMs != null &&
        pendingCommitEndMs != null &&
        loopStartMs == pendingCommitStartMs &&
        loopEndMs == pendingCommitEndMs

/** Track-local start of the active clip region (ms from WAV start). */
fun TrackEntity.effectiveLoopStartMs(): Long =
    if (!isLoop) {
        0L
    } else {
        loopStartMs.coerceAtLeast(0L)
    }

/** Track-local end of the active clip region (ms from WAV start). */
fun TrackEntity.effectiveLoopEndMs(): Long {
    val sourceDuration = sourceDurationMs()
    if (!isLoop) return sourceDuration
    if (sourceDuration <= 0L) return 0L
    val rawEnd = loopEndMs ?: sourceDuration
    return rawEnd.coerceIn(
        effectiveLoopStartMs() + TrackLoopRegionMinLengthMs.coerceAtMost(sourceDuration),
        sourceDuration,
    )
}

/**
 * Absolute project-timeline end from clip placement (offset + full source duration).
 * Loop region bounds do not trim the base timeline.
 */
fun TrackEntity.effectiveTimelineEndMs(): Long =
    timelineStartOffsetMs.coerceAtLeast(0L) + sourceDurationMs()

/** Track-local timeline position from the shared global playhead (ms). */
fun trackLocalTimelineMs(globalPlayheadMs: Long, timelineStartOffsetMs: Long): Long =
    globalPlayheadMs - timelineStartOffsetMs.coerceAtLeast(0L)

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

fun clampLoopRegionMs(
    loopStartMs: Long,
    loopEndMs: Long,
    sourceDurationMs: Long,
    minLengthMs: Long = TrackLoopRegionMinLengthMs,
): Pair<Long, Long> {
    if (sourceDurationMs <= 0L) return 0L to 0L
    val minLen = minLengthMs.coerceAtMost(sourceDurationMs)
    val end = loopEndMs.coerceIn(minLen, sourceDurationMs)
    val start = loopStartMs.coerceIn(0L, (end - minLen).coerceAtLeast(0L))
    val finalEnd = end.coerceAtLeast(start + minLen).coerceAtMost(sourceDurationMs)
    return start to finalEnd
}

data class LoopRegionOverlayFractions(
    val startFraction: Float,
    val endFraction: Float,
)

fun loopRegionOverlayFractions(
    loopStartMs: Long,
    loopEndMs: Long,
    sourceDurationMs: Long,
): LoopRegionOverlayFractions {
    if (sourceDurationMs <= 0L) {
        return LoopRegionOverlayFractions(startFraction = 0f, endFraction = 0f)
    }
    val startFraction =
        (loopStartMs.toDouble() / sourceDurationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    val endFraction =
        (loopEndMs.toDouble() / sourceDurationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    return LoopRegionOverlayFractions(startFraction = startFraction, endFraction = endFraction)
}

fun pointerXToSourceMs(pointerXInClipPx: Float, clipWidthPx: Float, sourceDurationMs: Long): Long {
    if (clipWidthPx <= 0f || sourceDurationMs <= 0L) return 0L
    val fraction =
        (pointerXInClipPx.toDouble() / clipWidthPx.toDouble()).coerceIn(0.0, 1.0)
    return (fraction * sourceDurationMs.toDouble()).toLong().coerceIn(0L, sourceDurationMs)
}

fun applyLoopRegionLeftHandleDrag(
    pointerMs: Long,
    loopEndMs: Long,
    sourceDurationMs: Long,
): Pair<Long, Long> {
    val maxStart = (loopEndMs - TrackLoopRegionMinLengthMs).coerceAtLeast(0L)
    val start = pointerMs.coerceIn(0L, maxStart)
    return clampLoopRegionMs(start, loopEndMs, sourceDurationMs)
}

fun applyLoopRegionRightHandleDrag(
    pointerMs: Long,
    loopStartMs: Long,
    sourceDurationMs: Long,
): Pair<Long, Long> {
    val minEnd = loopStartMs + TrackLoopRegionMinLengthMs
    val end = pointerMs.coerceIn(minEnd, sourceDurationMs)
    return clampLoopRegionMs(loopStartMs, end, sourceDurationMs)
}

fun applyLoopRegionMoveDrag(
    deltaMs: Long,
    loopStartMs: Long,
    loopEndMs: Long,
    sourceDurationMs: Long,
): Pair<Long, Long> {
    val length = loopEndMs - loopStartMs
    if (length <= 0L) return clampLoopRegionMs(loopStartMs, loopEndMs, sourceDurationMs)
    val maxStart = (sourceDurationMs - length).coerceAtLeast(0L)
    val start = (loopStartMs + deltaMs).coerceIn(0L, maxStart)
    return clampLoopRegionMs(start, start + length, sourceDurationMs)
}
