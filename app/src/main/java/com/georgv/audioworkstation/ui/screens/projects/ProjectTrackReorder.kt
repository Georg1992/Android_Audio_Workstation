package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.ui.geometry.Rect
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.layout.pageCount
import com.georgv.audioworkstation.ui.layout.pageEndExclusive
import com.georgv.audioworkstation.ui.layout.pageStartIndex
import com.georgv.audioworkstation.ui.layout.swapAdjacentAtBoundaryDown
import com.georgv.audioworkstation.ui.layout.swapAdjacentAtBoundaryUp

/** Lower = swap down earlier (see computeNeighborSwapTarget down-branch). */
internal const val ReorderVisibleNeighborEarlyFraction = 0.30f

private fun resolveDraggedGlobalIndex(
    tracks: List<TrackEntity>,
    draggingKey: String,
    knownGlobalIndex: Int,
): Int {
    if (knownGlobalIndex >= 0 &&
        knownGlobalIndex < tracks.size &&
        tracks[knownGlobalIndex].id == draggingKey
    ) {
        return knownGlobalIndex
    }
    return tracks.indexOfFirst { it.id == draggingKey }
}

/**
 * Returns a new track list with the dragged item moved one step toward an adjacent **on-page**
 * neighbor if finger geometry crosses the swap threshold; otherwise **null** (no layout-only
 * neighbors: missing [boundsByTrackId] for a neighbor skips that direction).
 */
fun neighborSwapOnPageOrNull(
    tracks: List<TrackEntity>,
    dragController: DragController,
    pageStartGlobalIndex: Int,
    pageEndExclusiveGlobal: Int,
    boundsByTrackId: Map<String, Rect>,
    /** If [tracks] at this index holds [dragController.draggingKey], avoids a full list scan. Use -1 when unknown. */
    knownGlobalIndex: Int = -1,
): List<TrackEntity>? {
    val key = dragController.draggingKey ?: return null
    val globalIdx = resolveDraggedGlobalIndex(tracks, key, knownGlobalIndex)
    if (globalIdx < 0 ||
        globalIdx < pageStartGlobalIndex ||
        globalIdx >= pageEndExclusiveGlobal
    ) {
        return null
    }

    val draggedCenterRootY =
        dragController.fingerY -
            dragController.dragOffset.y +
            dragController.overlayHeightPx / 2f

    val currentBounds = boundsByTrackId[key] ?: return null
    val currentTop = currentBounds.top
    val currentBottom = currentBounds.bottom

    val previousNeighborCenterY: Float? =
        if (globalIdx > pageStartGlobalIndex) {
            tracks.getOrNull(globalIdx - 1)?.id?.let { id -> boundsByTrackId[id]?.centerY() }
        } else {
            null
        }
    val nextNeighborCenterY: Float? =
        if (globalIdx < pageEndExclusiveGlobal - 1) {
            tracks.getOrNull(globalIdx + 1)?.id?.let { id -> boundsByTrackId[id]?.centerY() }
        } else {
            null
        }

    val targetIndex =
        computeNeighborSwapTarget(
            currentIndex = globalIdx,
            listSize = tracks.size,
            draggedCenterY = draggedCenterRootY,
            previousNeighborCenterY = previousNeighborCenterY,
            nextNeighborCenterY = nextNeighborCenterY,
            currentItemTop = currentTop,
            currentItemBottom = currentBottom,
        ) ?: return null

    return moveTrack(tracks, globalIdx, targetIndex)
}

/**
 * After a page-scroll preview, moves the dragged row across a page boundary once the finger
 * crosses the first/last visible row on the target page (not while still in the edge-hold band).
 */
internal fun crossPageBoundarySwapOrNull(
    tracks: List<TrackEntity>,
    dragController: DragController,
    pageStartGlobalIndex: Int,
    pageEndExclusiveGlobal: Int,
    boundsByTrackId: Map<String, Rect>,
    knownGlobalIndex: Int = -1,
): List<TrackEntity>? {
    val key = dragController.draggingKey ?: return null
    val globalIdx = resolveDraggedGlobalIndex(tracks, key, knownGlobalIndex)
    if (globalIdx < 0) return null

    val draggedBounds = boundsByTrackId[key] ?: return null
    val draggedCenterRootY =
        dragController.fingerY -
            dragController.dragOffset.y +
            dragController.overlayHeightPx / 2f

    if (globalIdx == pageStartGlobalIndex - 1 && pageStartGlobalIndex > 0) {
        val firstOnPage = tracks.getOrNull(pageStartGlobalIndex) ?: return null
        val firstBounds = boundsByTrackId[firstOnPage.id] ?: return null
        val target =
            computeNeighborSwapTarget(
                currentIndex = globalIdx,
                listSize = tracks.size,
                draggedCenterY = draggedCenterRootY,
                previousNeighborCenterY = null,
                nextNeighborCenterY = firstBounds.centerY(),
                currentItemTop = draggedBounds.top,
                currentItemBottom = draggedBounds.bottom,
            )
        if (target == globalIdx + 1) {
            return swapAdjacentAtBoundaryDown(tracks, globalIdx)
        }
    }

    if (globalIdx == pageEndExclusiveGlobal && pageEndExclusiveGlobal < tracks.size) {
        val lastOnPage = tracks.getOrNull(pageEndExclusiveGlobal - 1) ?: return null
        val lastBounds = boundsByTrackId[lastOnPage.id] ?: return null
        val target =
            computeNeighborSwapTarget(
                currentIndex = globalIdx,
                listSize = tracks.size,
                draggedCenterY = draggedCenterRootY,
                previousNeighborCenterY = lastBounds.centerY(),
                nextNeighborCenterY = null,
                currentItemTop = draggedBounds.top,
                currentItemBottom = draggedBounds.bottom,
            )
        if (target == globalIdx - 1) {
            return swapAdjacentAtBoundaryUp(tracks, globalIdx)
        }
    }

    return null
}

/**
 * On adjacent [VerticalPager] page entry during drag: swap the dragged row with the top/bottom
 * visible slot across the page boundary (scroll-only edge hold must have moved [targetPage] first).
 *
 * Entering next page: dragged at [fromPage] bottom ↔ top of [targetPage].
 * Entering previous page: dragged at [fromPage] top ↔ bottom of [targetPage].
 */
internal fun crossPageEnterSwapOrNull(
    tracks: List<TrackEntity>,
    draggedIndex: Int,
    fromPage: Int,
    targetPage: Int,
    pageSize: Int,
): List<TrackEntity>? {
    if (pageSize <= 0 || tracks.isEmpty()) return null
    if (draggedIndex !in tracks.indices) return null
    if (fromPage < 0 || targetPage < 0) return null

    val pageDelta = targetPage - fromPage
    if (pageDelta != 1 && pageDelta != -1) return null

    val trackCount = tracks.size
    val lastPage = pageCount(trackCount, pageSize).coerceAtLeast(1) - 1
    if (fromPage > lastPage || targetPage > lastPage) return null

    return when (pageDelta) {
        1 -> {
            val fromBottom = pageEndExclusive(trackCount, fromPage, pageSize) - 1
            if (draggedIndex != fromBottom || fromBottom >= tracks.lastIndex) return null
            swapAdjacentAtBoundaryDown(tracks, draggedIndex)
        }
        -1 -> {
            val fromTop = pageStartIndex(fromPage, pageSize)
            if (draggedIndex != fromTop) return null
            swapAdjacentAtBoundaryUp(tracks, draggedIndex)
        }
        else -> null
    }
}

private fun Rect.centerY(): Float = (top + bottom) / 2f

fun moveTrack(tracks: List<TrackEntity>, fromIndex: Int, toIndex: Int): List<TrackEntity> {
    return tracks.toMutableList().also {
        val item = it.removeAt(fromIndex)
        it.add(toIndex, item)
    }
}

fun computeNeighborSwapTarget(
    currentIndex: Int,
    listSize: Int,
    draggedCenterY: Float,
    previousNeighborCenterY: Float?,
    nextNeighborCenterY: Float?,
    currentItemTop: Float? = null,
    currentItemBottom: Float? = null,
): Int? {
    if (currentIndex < listSize - 1) {
        val nextCenter = nextNeighborCenterY
        if (nextCenter != null) {
            val bottom = currentItemBottom
            val thresholdDown =
                if (bottom != null) {
                    val span = nextCenter - bottom
                    if (span > 0f) {
                        bottom + ReorderVisibleNeighborEarlyFraction * span
                    } else {
                        nextCenter
                    }
                } else {
                    nextCenter
                }
            if (draggedCenterY > thresholdDown) return currentIndex + 1
        }
    }
    if (currentIndex > 0) {
        val prevCenter = previousNeighborCenterY
        if (prevCenter != null) {
            val top = currentItemTop
            val thresholdUp =
                if (top != null) {
                    val span = top - prevCenter
                    if (span > 0f) {
                        prevCenter + ReorderVisibleNeighborEarlyFraction * span
                    } else {
                        prevCenter
                    }
                } else {
                    prevCenter
                }
            if (draggedCenterY < thresholdUp) return currentIndex - 1
        }
    }
    return null
}
