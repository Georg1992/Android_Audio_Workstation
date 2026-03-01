package com.georgv.audioworkstation.ui.drag.reorder

import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.abs

/**
 * Computes drop index for a vertical LazyColumn list.
 *
 * Edge cases:
 * - Empty list or no draggable items: returns 0.
 * - Dragging above first item: returns 0.
 * - Dragging below last item: returns itemsCount.
 * - Finger outside visible area (e.g. overscroll): clamped to 0 or itemsCount using visible bounds.
 * - Single item / only dragged item visible: returns 0 (safe no-op drop).
 *
 * @param listState LazyListState of the list
 * @param draggedKey key (id) of the dragged item (must match Lazy item key)
 * @param draggedCenterY list-local Y of the drag position (e.g. finger or overlay center)
 * @param tracksStartIndex index in LazyColumn where draggable items begin (after headers)
 * @param itemsCount number of draggable items (not counting headers)
 * @return drop index in [0, itemsCount]; use with list that has dragged item excluded
 */
fun computeReorderDropIndex(
    listState: LazyListState,
    draggedKey: Any,
    draggedCenterY: Float,
    tracksStartIndex: Int,
    itemsCount: Int
): Int {
    if (itemsCount <= 0) return 0

    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return 0

    val trackItems = visible.filter { it.index >= tracksStartIndex }
    if (trackItems.isEmpty()) return 0

    val contentTop = trackItems.minOf { it.offset }
    val contentBottom = trackItems.maxOf { it.offset + it.size }

    if (draggedCenterY <= contentTop) return 0
    if (draggedCenterY >= contentBottom) return itemsCount

    val candidates = trackItems.filter { it.key != draggedKey }
    if (candidates.isEmpty()) return 0

    val nearest = candidates.minByOrNull { info ->
        val center = info.offset + info.size / 2f
        abs(center - draggedCenterY)
    } ?: return 0

    val nearestCenter = nearest.offset + nearest.size / 2f
    val insertAfter = draggedCenterY > nearestCenter

    val nearestItemIndex = (nearest.index - tracksStartIndex)
        .coerceIn(0, (itemsCount - 1).coerceAtLeast(0))

    val idx = if (insertAfter) nearestItemIndex + 1 else nearestItemIndex
    return idx.coerceIn(0, itemsCount)
}

