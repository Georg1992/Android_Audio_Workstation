package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.TimelineMaxDurationMs
import kotlin.math.max

/** Track-local trim start (ms from WAV start). */
fun TrackEntity.effectiveTrimStartMs(): Long = trimStartMs.coerceAtLeast(0L)

/** Track-local trim end (ms from WAV start). */
fun TrackEntity.effectiveTrimEndMs(): Long {
    val sourceDuration = sourceDurationMs()
    if (sourceDuration <= 0L) return 0L
    val rawEnd = trimEndMs ?: sourceDuration
    return rawEnd.coerceIn(
        effectiveTrimStartMs() + TrackLoopRegionMinLengthMs.coerceAtMost(sourceDuration),
        sourceDuration,
    )
}

/** Visible clip length after non-destructive trim. */
fun TrackEntity.trimmedClipDurationMs(): Long =
    (effectiveTrimEndMs() - effectiveTrimStartMs()).coerceAtLeast(0L)

fun clampTrimRegionMs(
    trimStartMs: Long,
    trimEndMs: Long,
    sourceDurationMs: Long,
): Pair<Long, Long> = clampLoopRegionMs(trimStartMs, trimEndMs, sourceDurationMs)

fun applyClipPositionMoveDrag(
    deltaMs: Long,
    clipStartOffsetMs: Long,
    trimmedDurationMs: Long,
    timelineLayoutDurationMs: Long,
): Long {
    if (trimmedDurationMs <= 0L || timelineLayoutDurationMs <= 0L) return clipStartOffsetMs.coerceAtLeast(0L)
    val maxStart = (timelineLayoutDurationMs - trimmedDurationMs).coerceAtLeast(0L)
    return (clipStartOffsetMs + deltaMs).coerceIn(0L, maxStart)
}

fun trackEditTimelineLayoutDurationMs(
    clipStartOffsetMs: Long,
    trimmedDurationMs: Long,
    sourceDurationMs: Long,
): Long {
    val clipEnd = clipStartOffsetMs.coerceAtLeast(0L) + trimmedDurationMs.coerceAtLeast(0L)
    val minimum = max(sourceDurationMs, 60_000L)
    return max(clipEnd + 30_000L, minimum).coerceAtMost(TimelineMaxDurationMs)
}

fun timelineMsFromPointerX(
    pointerXPx: Float,
    areaWidthPx: Float,
    layoutDurationMs: Long,
): Long {
    if (areaWidthPx <= 0f || layoutDurationMs <= 0L) return 0L
    val fraction = (pointerXPx.toDouble() / areaWidthPx.toDouble()).coerceIn(0.0, 1.0)
    return (fraction * layoutDurationMs.toDouble()).toLong().coerceIn(0L, layoutDurationMs)
}
