package com.georgv.audioworkstation.ui.layout

import com.georgv.audioworkstation.data.db.entities.TrackEntity

/** Returns the first index of tracks shown on [pageIndex]. */
fun pageStartIndex(pageIndex: Int, pageSize: Int): Int =
    (pageIndex.coerceAtLeast(0) * pageSize).coerceAtLeast(0)

/** One-past-last index of tracks on [pageIndex] (exclusive). */
fun pageEndExclusive(trackCount: Int, pageIndex: Int, pageSize: Int): Int =
    (pageStartIndex(pageIndex, pageSize) + pageSize).coerceAtMost(trackCount)

fun pageCount(trackCount: Int, pageSize: Int): Int =
    if (trackCount <= 0 || pageSize <= 0) {
        1
    } else {
        (trackCount + pageSize - 1) / pageSize
    }.coerceAtLeast(1)

fun pageIndexForTrackGlobalIndex(globalIndex: Int, pageSize: Int): Int =
    if (globalIndex < 0 || pageSize <= 0) {
        0
    } else {
        globalIndex / pageSize
    }

/**
 * Boundary with next slice: swaps [globalDragIndex] with [globalDragIndex + 1] when those rows are adjacent globally.
 */
fun swapAdjacentAtBoundaryDown(
    tracks: List<TrackEntity>,
    globalDragIndex: Int,
): List<TrackEntity>? {
    if (globalDragIndex >= tracks.lastIndex) return null
    return swapAdjacentGlobals(tracks, globalDragIndex, globalDragIndex + 1)
}

/** Boundary with previous slice: swaps predecessor with item at [globalDragIndex]. */
fun swapAdjacentAtBoundaryUp(
    tracks: List<TrackEntity>,
    globalDragIndex: Int,
): List<TrackEntity>? {
    if (globalDragIndex <= 0) return null
    return swapAdjacentGlobals(tracks, globalDragIndex - 1, globalDragIndex)
}

private fun swapAdjacentGlobals(
    tracks: List<TrackEntity>,
    i: Int,
    j: Int,
): List<TrackEntity>? {
    if (i !in tracks.indices || j !in tracks.indices || kotlin.math.abs(i - j) != 1) return null
    return tracks.toMutableList().also { m ->
        val t = m[i]
        m[i] = m[j]
        m[j] = t
    }
}
