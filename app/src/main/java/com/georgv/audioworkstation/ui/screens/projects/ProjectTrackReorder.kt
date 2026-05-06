package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.ui.geometry.Rect
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.drag.DragController

/**
 * When the next/previous neighbor is laid out on the same page (non-null bounds and centers),
 * swap after crossing the early fraction toward that neighbor center. Neighbors outside the laid-out
 * page window are omitted (no invisible-neighbor fallback).
 */
private const val ReorderVisibleNeighborEarlyFraction = 0.38f

fun maybeNeighborSwapOnPage(
    tracks: List<TrackEntity>,
    dragController: DragController,
    pageStartGlobalIndex: Int,
    pageEndExclusiveGlobal: Int,
    boundsByTrackId: Map<String, Rect>,
): List<TrackEntity>? {
    val key = dragController.draggingKey ?: return null
    val globalIdx = tracks.indexOfFirst { it.id == key }
    if (globalIdx < 0 ||
        globalIdx < pageStartGlobalIndex ||
        globalIdx >= pageEndExclusiveGlobal
    ) {
        return null
    }

    val draggedCenterRootY =
        dragController.fingerPos.y -
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
