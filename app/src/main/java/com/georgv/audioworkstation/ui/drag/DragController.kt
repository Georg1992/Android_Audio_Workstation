package com.georgv.audioworkstation.ui.drag

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * Minimal controller for drag state. No UI or list dependencies.
 * Drop/hover index is computed at the call site (e.g. computeReorderDropIndex in ui.drag.reorder).
 * from [fingerPos] and list layout.
 */
@Stable
class DragController {

    /** Key (e.g. track id) of the item being dragged, or null when idle. */
    var draggingKey: String? by mutableStateOf(null)
        private set

    /** Current drag position in the same coordinate system used for hit-testing (e.g. root or list-local). */
    var fingerPos: Offset by mutableStateOf(Offset.Zero)
        private set

    val isDragging: Boolean get() = draggingKey != null

    /** Start a drag. Call when long-press or drag gesture is detected. */
    fun start(key: String, startPos: Offset) {
        draggingKey = key
        fingerPos = startPos
    }

    /** Update drag position. No-op if not dragging. */
    fun update(pos: Offset) {
        if (!isDragging) return
        fingerPos = pos
    }

    /** End drag and clear state. Returns the dragged key for performing the drop; call moveTrack(draggedKey, dropIndex) at call site. */
    fun end(): String? {
        val key = draggingKey
        draggingKey = null
        fingerPos = Offset.Zero
        return key
    }

    /** Cancel drag without performing a drop. */
    fun cancel() {
        draggingKey = null
        fingerPos = Offset.Zero
    }
}

