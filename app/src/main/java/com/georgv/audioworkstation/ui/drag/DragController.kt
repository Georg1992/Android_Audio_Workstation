package com.georgv.audioworkstation.ui.drag

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * Controller for drag state used by reorder (e.g. track list).
 * Drop index is computed at call site via [com.georgv.audioworkstation.ui.drag.reorder.computeReorderDropIndex].
 */
@Stable
class DragController {

    /** Key (e.g. track id) of the item being dragged, or null when idle. */
    var draggingKey: String? by mutableStateOf(null)
        private set

    /** Current drag position in root (only Y is updated during drag; X is fixed at start). */
    var fingerPos: Offset by mutableStateOf(Offset.Zero)
        private set

    /**
     * Offset from finger to the dragged item's top-left at drag start.
     * Overlay top-left Y = fingerPos.y - dragOffset.y.
     */
    var dragOffset: Offset by mutableStateOf(Offset.Zero)
        private set

    /**
     * Overlay X in list-parent coordinates (px), set once at drag start.
     * X never changes during drag.
     */
    var fixedXInParentPx: Float by mutableStateOf(0f)
        private set

    /** Overlay size (px) from actual item bounds at drag start. Keeps overlay from stretching. */
    var overlayWidthPx: Float by mutableStateOf(0f)
        private set
    var overlayHeightPx: Float by mutableStateOf(0f)
        private set

    /** Computed drop index in [0, itemsCount]; null until computed. */
    var targetIndex: Int? by mutableStateOf(null)
        internal set

    val isDragging: Boolean get() = draggingKey != null

    /**
     * Start a drag. [fixedXInParentPx] and [overlayWidthPx]/[overlayHeightPx] from item bounds at start.
     */
    fun start(
        key: String,
        startPos: Offset,
        offsetFromFingerToItemTopLeft: Offset,
        fixedXInParentPx: Float,
        overlayWidthPx: Float,
        overlayHeightPx: Float
    ) {
        draggingKey = key
        dragOffset = offsetFromFingerToItemTopLeft
        this.fixedXInParentPx = fixedXInParentPx
        this.overlayWidthPx = overlayWidthPx
        this.overlayHeightPx = overlayHeightPx
        fingerPos = Offset(startPos.x, startPos.y)
        targetIndex = null
    }

    /** Update drag position (vertical only). Only fingerPos.y is updated. */
    fun update(pos: Offset) {
        if (!isDragging) return
        fingerPos = Offset(fingerPos.x, pos.y)
    }

    /** End drag and clear state. Returns the dragged key for performing the drop. */
    fun end(): String? {
        val key = draggingKey
        draggingKey = null
        fingerPos = Offset.Zero
        dragOffset = Offset.Zero
        fixedXInParentPx = 0f
        overlayWidthPx = 0f
        overlayHeightPx = 0f
        targetIndex = null
        return key
    }

    /** Cancel drag without performing a drop. */
    fun cancel() {
        draggingKey = null
        fingerPos = Offset.Zero
        dragOffset = Offset.Zero
        fixedXInParentPx = 0f
        overlayWidthPx = 0f
        overlayHeightPx = 0f
        targetIndex = null
    }
}
