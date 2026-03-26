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

    val neighborBelow = visible.find { it.index == currentIndex + 1 }
    if (shouldSwapWithNext(draggedCenterY, neighborBelow)) {
        return moveTrack(tracks, currentIndex, currentIndex + 1)
    }

    val neighborAbove = visible.find { it.index == currentIndex - 1 }
    if (shouldSwapWithPrevious(draggedCenterY, neighborAbove)) {
        return moveTrack(tracks, currentIndex, currentIndex - 1)
    }

    return null
}

fun isTrackFullyVisibleInLazyList(listState: LazyListState, itemIndex: Int): Boolean {
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.find { it.index == itemIndex } ?: return false
    return item.offset >= info.viewportStartOffset &&
        item.offset + item.size <= info.viewportEndOffset
}

private fun moveTrack(tracks: List<TrackEntity>, fromIndex: Int, toIndex: Int): List<TrackEntity> {
    return tracks.toMutableList().also {
        val item = it.removeAt(fromIndex)
        it.add(toIndex, item)
    }
}

private fun shouldSwapWithNext(
    draggedCenterY: Float,
    neighbor: LazyListItemInfo?
): Boolean = neighbor != null && draggedCenterY > neighbor.offset + neighbor.size / 2f

private fun shouldSwapWithPrevious(
    draggedCenterY: Float,
    neighbor: LazyListItemInfo?
): Boolean = neighbor != null && draggedCenterY < neighbor.offset + neighbor.size / 2f

fun fingerYInListSpace(
    fingerRootY: Float,
    listBoundsInRoot: Rect,
    viewportStartOffset: Int
): Float = fingerRootY - (listBoundsInRoot.top - viewportStartOffset)
