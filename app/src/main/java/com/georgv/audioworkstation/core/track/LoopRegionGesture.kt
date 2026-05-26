package com.georgv.audioworkstation.core.track

enum class LoopRegionDragMode {
    LeftHandle,
    RightHandle,
    MoveRegion,
    JumpLeft,
    JumpRight,
}

/** Active drag mode after [beginLoopRegionDragAtPointer] resolves jump modes. */
enum class LoopRegionActiveDragMode {
    LeftHandle,
    RightHandle,
    MoveRegion,
}

fun resolveLoopRegionDragMode(
    pointerMs: Long,
    loopStartMs: Long,
    loopEndMs: Long,
): LoopRegionDragMode =
    when {
        pointerMs < loopStartMs -> LoopRegionDragMode.JumpLeft
        pointerMs > loopEndMs -> LoopRegionDragMode.JumpRight
        else -> LoopRegionDragMode.MoveRegion
    }

fun loopRegionEdgePx(
    edgeMs: Long,
    clipWidthPx: Float,
    sourceDurationMs: Long,
): Float {
    if (clipWidthPx <= 0f || sourceDurationMs <= 0L) return 0f
    return clipWidthPx * (edgeMs.toDouble() / sourceDurationMs.toDouble()).toFloat()
}

/**
 * Maps touch x within the full clip (0..clipWidthPx ↔ 0..sourceDurationMs) to a drag mode.
 * Handle bands are centered on loop edges; outside the loop in ms selects jump modes.
 */
fun resolveLoopRegionDragModeFromPointerX(
    pointerXInClipPx: Float,
    clipWidthPx: Float,
    loopStartMs: Long,
    loopEndMs: Long,
    sourceDurationMs: Long,
    handleHitHalfWidthPx: Float,
): LoopRegionDragMode {
    if (clipWidthPx <= 0f || sourceDurationMs <= 0L) {
        return LoopRegionDragMode.MoveRegion
    }
    val pointerMs =
        pointerXToSourceMs(
            pointerXInClipPx = pointerXInClipPx,
            clipWidthPx = clipWidthPx,
            sourceDurationMs = sourceDurationMs,
        )
    when {
        pointerMs < loopStartMs -> return LoopRegionDragMode.JumpLeft
        pointerMs > loopEndMs -> return LoopRegionDragMode.JumpRight
    }
    if (handleHitHalfWidthPx <= 0f) {
        return LoopRegionDragMode.MoveRegion
    }
    val startPx = loopRegionEdgePx(loopStartMs, clipWidthPx, sourceDurationMs)
    val endPx = loopRegionEdgePx(loopEndMs, clipWidthPx, sourceDurationMs)
    val leftBandMinX = startPx - handleHitHalfWidthPx
    val leftBandMaxX = startPx + handleHitHalfWidthPx
    val rightBandMinX = endPx - handleHitHalfWidthPx
    val rightBandMaxX = endPx + handleHitHalfWidthPx
    if (leftBandMaxX >= rightBandMinX) {
        return if (pointerXInClipPx <= (startPx + endPx) / 2f) {
            LoopRegionDragMode.LeftHandle
        } else {
            LoopRegionDragMode.RightHandle
        }
    }
    return when {
        pointerXInClipPx in leftBandMinX..leftBandMaxX -> LoopRegionDragMode.LeftHandle
        pointerXInClipPx in rightBandMinX..rightBandMaxX -> LoopRegionDragMode.RightHandle
        else -> LoopRegionDragMode.MoveRegion
    }
}

data class LoopRegionDragBeginResult(
    val activeMode: LoopRegionActiveDragMode,
    val loopStartMs: Long,
    val loopEndMs: Long,
    val anchorStartMs: Long,
    val anchorEndMs: Long,
    val moveOriginPointerMs: Long,
)

fun beginLoopRegionDragAtPointer(
    pointerXInClipPx: Float,
    clipWidthPx: Float,
    loopStartMs: Long,
    loopEndMs: Long,
    sourceDurationMs: Long,
    handleHitHalfWidthPx: Float,
): LoopRegionDragBeginResult {
    val pointerMs =
        pointerXToSourceMs(
            pointerXInClipPx = pointerXInClipPx,
            clipWidthPx = clipWidthPx,
            sourceDurationMs = sourceDurationMs,
        )
    val mode =
        resolveLoopRegionDragModeFromPointerX(
            pointerXInClipPx = pointerXInClipPx,
            clipWidthPx = clipWidthPx,
            loopStartMs = loopStartMs,
            loopEndMs = loopEndMs,
            sourceDurationMs = sourceDurationMs,
            handleHitHalfWidthPx = handleHitHalfWidthPx,
        )
    return when (mode) {
        LoopRegionDragMode.JumpLeft,
        LoopRegionDragMode.LeftHandle,
        -> {
            val (start, end) =
                applyLoopRegionLeftHandleDrag(
                    pointerMs = pointerMs,
                    loopEndMs = loopEndMs,
                    sourceDurationMs = sourceDurationMs,
                )
            LoopRegionDragBeginResult(
                activeMode = LoopRegionActiveDragMode.LeftHandle,
                loopStartMs = start,
                loopEndMs = end,
                anchorStartMs = start,
                anchorEndMs = end,
                moveOriginPointerMs = pointerMs,
            )
        }
        LoopRegionDragMode.JumpRight,
        LoopRegionDragMode.RightHandle,
        -> {
            val (start, end) =
                applyLoopRegionRightHandleDrag(
                    pointerMs = pointerMs,
                    loopStartMs = loopStartMs,
                    sourceDurationMs = sourceDurationMs,
                )
            LoopRegionDragBeginResult(
                activeMode = LoopRegionActiveDragMode.RightHandle,
                loopStartMs = start,
                loopEndMs = end,
                anchorStartMs = start,
                anchorEndMs = end,
                moveOriginPointerMs = pointerMs,
            )
        }
        LoopRegionDragMode.MoveRegion ->
            LoopRegionDragBeginResult(
                activeMode = LoopRegionActiveDragMode.MoveRegion,
                loopStartMs = loopStartMs,
                loopEndMs = loopEndMs,
                anchorStartMs = loopStartMs,
                anchorEndMs = loopEndMs,
                moveOriginPointerMs = pointerMs,
            )
    }
}

fun applyLoopRegionActiveDrag(
    activeMode: LoopRegionActiveDragMode,
    pointerMs: Long,
    anchorStartMs: Long,
    anchorEndMs: Long,
    moveOriginPointerMs: Long,
    sourceDurationMs: Long,
): Pair<Long, Long> =
    when (activeMode) {
        LoopRegionActiveDragMode.LeftHandle ->
            applyLoopRegionLeftHandleDrag(
                pointerMs = pointerMs,
                loopEndMs = anchorEndMs,
                sourceDurationMs = sourceDurationMs,
            )
        LoopRegionActiveDragMode.RightHandle ->
            applyLoopRegionRightHandleDrag(
                pointerMs = pointerMs,
                loopStartMs = anchorStartMs,
                sourceDurationMs = sourceDurationMs,
            )
        LoopRegionActiveDragMode.MoveRegion ->
            applyLoopRegionMoveDrag(
                deltaMs = pointerMs - moveOriginPointerMs,
                loopStartMs = anchorStartMs,
                loopEndMs = anchorEndMs,
                sourceDurationMs = sourceDurationMs,
            )
    }

/** Outside-region tap: jump the nearest handle to [pointerMs] before drag begins. */
fun applyLoopRegionOutsideTap(
    pointerMs: Long,
    loopStartMs: Long,
    loopEndMs: Long,
    sourceDurationMs: Long,
): Pair<Long, Long> =
    when (resolveLoopRegionDragMode(pointerMs, loopStartMs, loopEndMs)) {
        LoopRegionDragMode.JumpLeft ->
            applyLoopRegionLeftHandleDrag(pointerMs, loopEndMs, sourceDurationMs)
        LoopRegionDragMode.JumpRight ->
            applyLoopRegionRightHandleDrag(pointerMs, loopStartMs, sourceDurationMs)
        LoopRegionDragMode.MoveRegion,
        LoopRegionDragMode.LeftHandle,
        LoopRegionDragMode.RightHandle,
        ->
            loopStartMs to loopEndMs
    }
