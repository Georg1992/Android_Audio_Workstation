package com.georgv.audioworkstation.ui.drag

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/** Minimal drag state for live adjacent-swap reorder. */
@Stable
class DragController {

    var draggingKey: String? by mutableStateOf(null)
        private set

    /** Current finger position in root coordinates. */
    var fingerPos: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Finger minus item top-left at drag start; overlay top Y = fingerPos.y - dragOffset.y */
    var dragOffset: Offset by mutableStateOf(Offset.Zero)
        private set

    var fixedXInParentPx: Float by mutableFloatStateOf(0f)
        private set

    var overlayWidthPx: Float by mutableFloatStateOf(0f)
        private set

    var overlayHeightPx: Float by mutableFloatStateOf(0f)
        private set

    val isDragging: Boolean get() = draggingKey != null

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
        fingerPos = startPos
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
    }
}
