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
fun resolveLoopRegionDragModeFromAreaPointer(
    areaXPx: Float,
    clipStartPx: Float,
    clipWidthPx: Float,
    leftHandleAreaX: Float,
    rightHandleAreaX: Float,
    loopStartMs: Long,
    loopEndMs: Long,
    sourceDurationMs: Long,
    handleHitHalfWidthPx: Float,
    handleTriangleHalfWidthPx: Float = 0f,
): LoopRegionDragMode {
    if (clipWidthPx <= 0f || sourceDurationMs <= 0L) {
        return LoopRegionDragMode.MoveRegion
    }
    val handleGrabHalfWidthPx = handleHitHalfWidthPx + handleTriangleHalfWidthPx
    if (handleGrabHalfWidthPx > 0f) {
        if (kotlin.math.abs(areaXPx - leftHandleAreaX) <= handleGrabHalfWidthPx) {
            return LoopRegionDragMode.LeftHandle
        }
        if (kotlin.math.abs(areaXPx - rightHandleAreaX) <= handleGrabHalfWidthPx) {
            return LoopRegionDragMode.RightHandle
        }
    }
    val pointerXInClipPx = (areaXPx - clipStartPx).coerceIn(0f, clipWidthPx)
    return resolveLoopRegionDragModeFromPointerX(
        pointerXInClipPx = pointerXInClipPx,
        clipWidthPx = clipWidthPx,
        loopStartMs = loopStartMs,
        loopEndMs = loopEndMs,
        sourceDurationMs = sourceDurationMs,
        handleHitHalfWidthPx = handleHitHalfWidthPx,
    )
}

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
    val clampedPointerX = pointerXInClipPx.coerceIn(0f, clipWidthPx)
    val pointerMs =
        pointerXToSourceMs(
            pointerXInClipPx = clampedPointerX,
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
    if (startPx <= handleHitHalfWidthPx && clampedPointerX <= startPx + handleHitHalfWidthPx * 1.5f) {
        return LoopRegionDragMode.LeftHandle
    }
    if (endPx >= clipWidthPx - handleHitHalfWidthPx && clampedPointerX >= endPx - handleHitHalfWidthPx * 1.5f) {
        return LoopRegionDragMode.RightHandle
    }
    val leftBandMinX = (startPx - handleHitHalfWidthPx).coerceAtLeast(0f)
    val leftBandMaxX = (startPx + handleHitHalfWidthPx).coerceAtMost(clipWidthPx)
    val rightBandMinX = (endPx - handleHitHalfWidthPx).coerceAtLeast(0f)
    val rightBandMaxX = (endPx + handleHitHalfWidthPx).coerceAtMost(clipWidthPx)
    if (leftBandMaxX >= rightBandMinX) {
        val loopWidthPx = (endPx - startPx).coerceAtLeast(1f)
        val edgeHalfWidthPx = minOf(handleHitHalfWidthPx, loopWidthPx * 0.18f)
        return when {
            clampedPointerX <= startPx + edgeHalfWidthPx -> LoopRegionDragMode.LeftHandle
            clampedPointerX >= endPx - edgeHalfWidthPx -> LoopRegionDragMode.RightHandle
            else -> LoopRegionDragMode.MoveRegion
        }
    }
    return when {
        clampedPointerX in leftBandMinX..leftBandMaxX -> LoopRegionDragMode.LeftHandle
        clampedPointerX in rightBandMinX..rightBandMaxX -> LoopRegionDragMode.RightHandle
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
    val clampedPointerX = pointerXInClipPx.coerceIn(0f, clipWidthPx)
    val pointerMs =
        pointerXToSourceMs(
            pointerXInClipPx = clampedPointerX,
            clipWidthPx = clipWidthPx,
            sourceDurationMs = sourceDurationMs,
        )
    val mode =
        resolveLoopRegionDragModeFromPointerX(
            pointerXInClipPx = clampedPointerX,
            clipWidthPx = clipWidthPx,
            loopStartMs = loopStartMs,
            loopEndMs = loopEndMs,
            sourceDurationMs = sourceDurationMs,
            handleHitHalfWidthPx = handleHitHalfWidthPx,
        )
    return loopRegionDragBeginForMode(
        mode = mode,
        pointerMs = pointerMs,
        loopStartMs = loopStartMs,
        loopEndMs = loopEndMs,
        sourceDurationMs = sourceDurationMs,
    )
}

fun beginLoopRegionDragAtAreaPointer(
    areaXPx: Float,
    clipStartPx: Float,
    clipWidthPx: Float,
    leftHandleAreaX: Float,
    rightHandleAreaX: Float,
    loopStartMs: Long,
    loopEndMs: Long,
    sourceDurationMs: Long,
    handleHitHalfWidthPx: Float,
    handleTriangleHalfWidthPx: Float = 0f,
): LoopRegionDragBeginResult {
    val clampedPointerX = (areaXPx - clipStartPx).coerceIn(0f, clipWidthPx)
    val pointerMs =
        pointerXToSourceMs(
            pointerXInClipPx = clampedPointerX,
            clipWidthPx = clipWidthPx,
            sourceDurationMs = sourceDurationMs,
        )
    val mode =
        resolveLoopRegionDragModeFromAreaPointer(
            areaXPx = areaXPx,
            clipStartPx = clipStartPx,
            clipWidthPx = clipWidthPx,
            leftHandleAreaX = leftHandleAreaX,
            rightHandleAreaX = rightHandleAreaX,
            loopStartMs = loopStartMs,
            loopEndMs = loopEndMs,
            sourceDurationMs = sourceDurationMs,
            handleHitHalfWidthPx = handleHitHalfWidthPx,
            handleTriangleHalfWidthPx = handleTriangleHalfWidthPx,
        )
    return loopRegionDragBeginForMode(
        mode = mode,
        pointerMs = pointerMs,
        loopStartMs = loopStartMs,
        loopEndMs = loopEndMs,
        sourceDurationMs = sourceDurationMs,
    )
}

private fun loopRegionDragBeginForMode(
    mode: LoopRegionDragMode,
    pointerMs: Long,
    loopStartMs: Long,
    loopEndMs: Long,
    sourceDurationMs: Long,
): LoopRegionDragBeginResult =
    when (mode) {
        LoopRegionDragMode.JumpLeft -> {
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
        LoopRegionDragMode.LeftHandle ->
            LoopRegionDragBeginResult(
                activeMode = LoopRegionActiveDragMode.LeftHandle,
                loopStartMs = loopStartMs,
                loopEndMs = loopEndMs,
                anchorStartMs = loopStartMs,
                anchorEndMs = loopEndMs,
                moveOriginPointerMs = pointerMs,
            )
        LoopRegionDragMode.JumpRight -> {
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
        LoopRegionDragMode.RightHandle ->
            LoopRegionDragBeginResult(
                activeMode = LoopRegionActiveDragMode.RightHandle,
                loopStartMs = loopStartMs,
                loopEndMs = loopEndMs,
                anchorStartMs = loopStartMs,
                anchorEndMs = loopEndMs,
                moveOriginPointerMs = pointerMs,
            )
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
