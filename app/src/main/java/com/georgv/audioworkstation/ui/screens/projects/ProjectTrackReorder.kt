package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Rect
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.drag.DragController

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

    val targetIndex = computeNeighborSwapTarget(
        currentIndex = currentIndex,
        draggedCenterY = draggedCenterY,
        previousNeighborCenterY = visible.find { it.index == currentIndex - 1 }?.centerY(),
        nextNeighborCenterY = visible.find { it.index == currentIndex + 1 }?.centerY()
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
    draggedCenterY: Float,
    previousNeighborCenterY: Float?,
    nextNeighborCenterY: Float?
): Int? {
    if (nextNeighborCenterY != null && draggedCenterY > nextNeighborCenterY) {
        return currentIndex + 1
    }
    if (previousNeighborCenterY != null && draggedCenterY < previousNeighborCenterY) {
        return currentIndex - 1
    }
    return null
}

fun fingerYInListSpace(
    fingerRootY: Float,
    listBoundsInRoot: Rect,
    viewportStartOffset: Int
): Float = fingerRootY - (listBoundsInRoot.top - viewportStartOffset)

private fun LazyListItemInfo.centerY(): Float = offset + size / 2f
