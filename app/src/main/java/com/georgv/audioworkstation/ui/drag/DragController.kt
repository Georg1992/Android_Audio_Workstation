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

    /** Finger Y in root coordinates; only Y changes during drag (narrower invalidation than full [Offset]). */
    var fingerY: Float by mutableFloatStateOf(0f)
        private set

    /** Finger minus item top-left at drag start; overlay top Y = fingerY - dragOffset.y */
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
        fingerY = startPos.y
    }

    fun update(pos: Offset) {
        if (!isDragging) return
        fingerY = pos.y
    }

    fun end() {
        draggingKey = null
        fingerY = 0f
        dragOffset = Offset.Zero
        fixedXInParentPx = 0f
        overlayWidthPx = 0f
        overlayHeightPx = 0f
    }
}
