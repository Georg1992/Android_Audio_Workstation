package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Rect
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.drag.DragController

/**
 * When the next/previous neighbor is visible, swap after the ghost crosses this fraction of the
 * distance from the current row edge to the neighbor center (not the full half-row to center).
 * Keeps one-step swaps but feels earlier on tall rows; stay well below 1f to limit jitter.
 */
private const val ReorderVisibleNeighborEarlyFraction = 0.38f

fun maybeNeighborSwap(
    tracks: List<TrackEntity>,
    dragController: DragController,
    listState: LazyListState,
    listBoundsInRoot: Rect
): List<TrackEntity>? {
    val key = dragController.draggingKey ?: return null
    val currentIndex = tracks.indexOfFirst { it.id == key }
    if (currentIndex < 0) return null

    val layoutInfo = listState.layoutInfo
    val draggedCenterY = fingerYInListSpace(
        dragController.fingerPos.y,
        listBoundsInRoot,
        layoutInfo.viewportStartOffset
    ) - dragController.dragOffset.y + dragController.overlayHeightPx / 2f

    val visible = layoutInfo.visibleItemsInfo
    val currentLayout = visible.find { it.index == currentIndex }

    val targetIndex = computeNeighborSwapTarget(
        currentIndex = currentIndex,
        listSize = tracks.size,
        draggedCenterY = draggedCenterY,
        previousNeighborCenterY = visible.find { it.index == currentIndex - 1 }?.centerY(),
        nextNeighborCenterY = visible.find { it.index == currentIndex + 1 }?.centerY(),
        currentItemTop = currentLayout?.offset?.toFloat(),
        currentItemBottom = currentLayout?.let { (it.offset + it.size).toFloat() },
    ) ?: return null

    return moveTrack(tracks, currentIndex, targetIndex)
}

fun isTrackFullyVisibleInLazyList(listState: LazyListState, itemIndex: Int): Boolean {
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.find { it.index == itemIndex } ?: return false
    return item.offset >= info.viewportStartOffset &&
        item.offset + item.size <= info.viewportEndOffset
}

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
            val thresholdDown = if (bottom != null) {
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
        } else if (currentItemBottom != null && draggedCenterY > currentItemBottom) {
            return currentIndex + 1
        }
    }
    if (currentIndex > 0) {
        val prevCenter = previousNeighborCenterY
        if (prevCenter != null) {
            val top = currentItemTop
            val thresholdUp = if (top != null) {
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
        } else if (currentItemTop != null && draggedCenterY < currentItemTop) {
            return currentIndex - 1
        }
    }
    return null
}

fun fingerYInListSpace(
    fingerRootY: Float,
    listBoundsInRoot: Rect,
    viewportStartOffset: Int
): Float = fingerRootY - (listBoundsInRoot.top - viewportStartOffset)

private fun LazyListItemInfo.centerY(): Float = offset + size / 2f
