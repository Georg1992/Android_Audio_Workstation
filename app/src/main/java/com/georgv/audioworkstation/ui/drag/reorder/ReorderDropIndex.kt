package com.georgv.audioworkstation.ui.drag.reorder

import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.abs

/**
 * Target insert index [0, itemsCount] from list-local Y of the dragged row centre.
 * [tracksStartIndex] offsets lazy indices when the list has leading non-track items (default 0).
 */
fun computeReorderDropIndex(
    listState: LazyListState,
    draggedKey: Any,
    draggedCenterY: Float,
    itemsCount: Int,
    tracksStartIndex: Int = 0
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

    val nearest = trackItems.minByOrNull { info ->
        val center = info.offset + info.size / 2f
        abs(center - draggedCenterY)
    } ?: return 0

    if (nearest.key == draggedKey) {
        return (nearest.index - tracksStartIndex).coerceIn(0, (itemsCount - 1).coerceAtLeast(0))
    }

    val nearestCenter = nearest.offset + nearest.size / 2f
    val insertAfter = draggedCenterY > nearestCenter

    val nearestItemIndex = (nearest.index - tracksStartIndex)
        .coerceIn(0, (itemsCount - 1).coerceAtLeast(0))

    val idx = if (insertAfter) nearestItemIndex + 1 else nearestItemIndex
    return idx.coerceIn(0, itemsCount)
}
