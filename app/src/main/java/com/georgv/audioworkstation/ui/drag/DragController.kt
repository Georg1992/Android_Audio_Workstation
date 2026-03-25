package com.georgv.audioworkstation.ui.drag

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/** Drag state for list reorder; drop index is derived in the screen (see reorder package). */
@Stable
class DragController {

    var draggingKey: String? by mutableStateOf(null)
        private set

    var fingerPos: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Finger minus item top-left at start; overlay top Y = fingerPos.y - dragOffset.y */
    var dragOffset: Offset by mutableStateOf(Offset.Zero)
        private set

    var fixedXInParentPx: Float by mutableFloatStateOf(0f)
        private set

    var overlayWidthPx: Float by mutableFloatStateOf(0f)
        private set

    var overlayHeightPx: Float by mutableFloatStateOf(0f)
        private set

    var dragAnchorYRoot: Float by mutableFloatStateOf(0f)
        private set
    
    var reorderAnchorCenterListLocalY: Float by mutableFloatStateOf(Float.NaN)
        private set

    val isDragging: Boolean get() = draggingKey != null

    fun start(
        key: String,
        startPos: Offset,
        offsetFromFingerToItemTopLeft: Offset,
        fixedXInParentPx: Float,
        overlayWidthPx: Float,
        overlayHeightPx: Float,
        fingerListLocalY: Float
    ) {
        draggingKey = key
        dragOffset = offsetFromFingerToItemTopLeft
        this.fixedXInParentPx = fixedXInParentPx
        this.overlayWidthPx = overlayWidthPx
        this.overlayHeightPx = overlayHeightPx
        fingerPos = Offset(startPos.x, startPos.y)
        dragAnchorYRoot = startPos.y
        reorderAnchorCenterListLocalY = fingerListLocalY - dragOffset.y + overlayHeightPx / 2f
    }

    fun update(pos: Offset) {
        if (!isDragging) return
        fingerPos = Offset(fingerPos.x, pos.y)
    }

    fun end() {
        draggingKey = null
        fingerPos = Offset.Zero
        dragOffset = Offset.Zero
        fixedXInParentPx = 0f
        overlayWidthPx = 0f
        overlayHeightPx = 0f
        dragAnchorYRoot = 0f
        reorderAnchorCenterListLocalY = Float.NaN
    }
}
