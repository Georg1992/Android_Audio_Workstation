package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.ui.geometry.Rect
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.layout.pageEndExclusive
import com.georgv.audioworkstation.ui.layout.pageStartIndex

internal fun trackListOrderChanged(
    current: List<TrackEntity>,
    reordered: List<TrackEntity>,
): Boolean {
    if (reordered.size != current.size) return true
    return reordered.indices.any { reordered[it].id != current[it].id }
}

internal fun resolveDraggingGlobalIndex(
    tracks: List<TrackEntity>,
    draggingKey: String,
    cachedIndex: Int,
): Int {
    if (cachedIndex >= 0 && cachedIndex < tracks.size && tracks[cachedIndex].id == draggingKey) {
        return cachedIndex
    }
    return tracks.indexOfFirst { it.id == draggingKey }
}

internal fun computeDropOverlayStartY(
    fingerY: Float,
    dragOffsetY: Float,
    parentTop: Float,
    parentHeight: Float,
    overlayHeightPx: Float,
): Float =
    (fingerY - dragOffsetY - parentTop).coerceIn(
        0f,
        (parentHeight - overlayHeightPx).coerceAtLeast(0f),
    )

internal fun dropOverlayNeedsImmediateFinish(
    startY: Float,
    overlayWidthPx: Float,
    overlayHeightPx: Float,
    parentTop: Float,
): Boolean =
    !startY.isFinite() ||
        overlayWidthPx <= 0f ||
        overlayHeightPx <= 0f ||
        !parentTop.isFinite()

internal fun computeDropSettleTargetTranslationY(
    globalIndex: Int,
    sliceStart: Int,
    sliceEnd: Int,
    listBoundsTop: Float,
    parentTop: Float,
    rowStride: Float,
    overlayStartY: Float,
): Float {
    if (globalIndex !in sliceStart until sliceEnd) return overlayStartY
    val indexInPage = globalIndex - sliceStart
    val targetTopRoot = listBoundsTop + indexInPage * rowStride
    return targetTopRoot - parentTop
}

internal fun buildDropSettleSnapOrNull(
    draggingKey: String,
    tracks: List<TrackEntity>,
    dragController: DragController,
    listParentBounds: Rect,
    listBounds: Rect,
    selectedTrackIds: Set<String>,
    recordingTrackId: String?,
    pagerCurrentPage: Int,
    pageSize: Int,
    rowStridePx: Float,
    allocateSettleUid: () -> Long,
): DropSettleSnap? {
    val trackEntity = tracks.find { it.id == draggingKey } ?: return null
    val parentTop = listParentBounds.top
    val parentHeight = listParentBounds.bottom - listParentBounds.top
    val overlayWidthPx = dragController.overlayWidthPx
    val overlayHeightPx = dragController.overlayHeightPx
    val startY =
        computeDropOverlayStartY(
            fingerY = dragController.fingerY,
            dragOffsetY = dragController.dragOffset.y,
            parentTop = parentTop,
            parentHeight = parentHeight,
            overlayHeightPx = overlayHeightPx,
        )
    val globalIndex = tracks.indexOfFirst { it.id == draggingKey }
    val pageIndex = pagerCurrentPage.coerceAtLeast(0)
    val sliceStart = pageStartIndex(pageIndex, pageSize)
    val sliceEnd = pageEndExclusive(tracks.size, pageIndex, pageSize)
    val targetY =
        computeDropSettleTargetTranslationY(
            globalIndex = globalIndex,
            sliceStart = sliceStart,
            sliceEnd = sliceEnd,
            listBoundsTop = listBounds.top,
            parentTop = parentTop,
            rowStride = rowStridePx,
            overlayStartY = startY,
        )
    val canSettle =
        !dropOverlayNeedsImmediateFinish(startY, overlayWidthPx, overlayHeightPx, parentTop) &&
            !listBounds.isEmpty &&
            globalIndex >= 0 &&
            targetY.isFinite()
    return if (canSettle) {
        DropSettleSnap(
            settleUid = allocateSettleUid(),
            trackId = draggingKey,
            track = trackEntity,
            isSelected = selectedTrackIds.contains(draggingKey),
            isRecording = recordingTrackId == draggingKey,
            gain = trackEntity.gain,
            fixedXInParentPx = dragController.fixedXInParentPx,
            overlayWidthPx = overlayWidthPx,
            overlayHeightPx = overlayHeightPx,
            startTranslationYPx = startY,
            targetTranslationYPx = targetY,
        )
    } else {
        null
    }
}

internal fun planDropCompletion(
    draggingKey: String,
    tracks: List<TrackEntity>,
    dragController: DragController,
    listParentBounds: Rect,
    listBounds: Rect,
    selectedTrackIds: Set<String>,
    recordingTrackId: String?,
    pagerCurrentPage: Int,
    pageSize: Int,
    rowStridePx: Float,
    allocateSettleUid: () -> Long,
): DropCompletionPlan {
    val snap =
        buildDropSettleSnapOrNull(
            draggingKey = draggingKey,
            tracks = tracks,
            dragController = dragController,
            listParentBounds = listParentBounds,
            listBounds = listBounds,
            selectedTrackIds = selectedTrackIds,
            recordingTrackId = recordingTrackId,
            pagerCurrentPage = pagerCurrentPage,
            pageSize = pageSize,
            rowStridePx = rowStridePx,
            allocateSettleUid = allocateSettleUid,
        )
    return if (snap == null) DropCompletionPlan.FinishImmediate else DropCompletionPlan.Settle(snap)
}
