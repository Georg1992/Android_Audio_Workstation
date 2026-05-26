package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.core.track.LoopRegionActiveDragMode
import com.georgv.audioworkstation.core.track.applyLoopRegionLeftHandleDrag
import com.georgv.audioworkstation.core.track.applyLoopRegionMoveDrag
import com.georgv.audioworkstation.core.track.applyLoopRegionRightHandleDrag
import com.georgv.audioworkstation.core.track.beginLoopRegionDragAtAreaPointer
import com.georgv.audioworkstation.core.track.clampLoopRegionMs
import com.georgv.audioworkstation.core.track.loopRegionDisplayBoundsMs
import com.georgv.audioworkstation.core.track.loopRegionPendingCommitResolved
import com.georgv.audioworkstation.ui.theme.AppColors

private val LoopRegionHandleLineWidthDp = 1.dp
private val LoopRegionHandleTriangleWidthDp = 10.dp
private val LoopRegionHandleTriangleHeightDp = 8.dp
private val LoopRegionHandleHitWidthDp = 28.dp
private const val LoopRegionFillAlpha = 0.38f

private enum class LoopRegionHandleSide {
    Start,
    End,
}

@Composable
fun LoopRegionEditor(
    sourceDurationMs: Long,
    loopStartMs: Long,
    loopEndMs: Long,
    editingEnabled: Boolean,
    onLoopRegionCommit: (loopStartMs: Long, loopEndMs: Long) -> Unit,
    waveformAreaWidthPx: Float,
    timelineScale: TimelineLaneScale,
    sourceFitScale: TimelineLaneScale,
    loopEditFocusActive: Boolean,
    persistentViewportZoomed: Boolean = false,
    onLoopRegionEditFocusChanged: (Boolean) -> Unit,
    onLoopRegionPreviewChanged: (loopStartMs: Long, loopEndMs: Long) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (sourceDurationMs <= 0L) return

    var previewStartMs by remember { mutableLongStateOf(loopStartMs) }
    var previewEndMs by remember { mutableLongStateOf(loopEndMs) }
    var isDragging by remember { mutableStateOf(false) }
    var pendingCommitStartMs by remember { mutableStateOf<Long?>(null) }
    var pendingCommitEndMs by remember { mutableStateOf<Long?>(null) }

    val latestLoopStartMs by rememberUpdatedState(loopStartMs)
    val latestLoopEndMs by rememberUpdatedState(loopEndMs)
    val latestWaveformAreaWidthPx by rememberUpdatedState(waveformAreaWidthPx)
    val latestTimelineScale by rememberUpdatedState(timelineScale)
    val latestSourceFitScale by rememberUpdatedState(sourceFitScale)
    val latestPersistentViewportZoomed by rememberUpdatedState(persistentViewportZoomed)
    val latestOnLoopRegionPreviewChanged by rememberUpdatedState(onLoopRegionPreviewChanged)

    LaunchedEffect(latestLoopStartMs, latestLoopEndMs, isDragging, pendingCommitStartMs, pendingCommitEndMs) {
        if (isDragging) return@LaunchedEffect
        if (
            loopRegionPendingCommitResolved(
                loopStartMs = latestLoopStartMs,
                loopEndMs = latestLoopEndMs,
                pendingCommitStartMs = pendingCommitStartMs,
                pendingCommitEndMs = pendingCommitEndMs,
            )
        ) {
            pendingCommitStartMs = null
            pendingCommitEndMs = null
            previewStartMs = latestLoopStartMs
            previewEndMs = latestLoopEndMs
            return@LaunchedEffect
        }
        if (pendingCommitStartMs == null) {
            previewStartMs = latestLoopStartMs
            previewEndMs = latestLoopEndMs
        }
    }

    val (displayStartMs, displayEndMs) =
        loopRegionDisplayBoundsMs(
            isDragging = isDragging,
            previewStartMs = previewStartMs,
            previewEndMs = previewEndMs,
            pendingCommitStartMs = pendingCommitStartMs,
            pendingCommitEndMs = pendingCommitEndMs,
            loopStartMs = loopStartMs,
            loopEndMs = loopEndMs,
        )

    SideEffect {
        latestOnLoopRegionPreviewChanged(displayStartMs, displayEndMs)
    }

    val displayScale =
        if (latestPersistentViewportZoomed || loopEditFocusActive || isDragging) {
            latestSourceFitScale
        } else {
            latestTimelineScale
        }

    val density = LocalDensity.current
    val handleHitHalfWidthPx = with(density) { LoopRegionHandleHitWidthDp.toPx() / 2f }
    val handleTriangleHalfWidthPx = with(density) { LoopRegionHandleTriangleWidthDp.toPx() / 2f }
    val handleTriangleWidthPx = with(density) { LoopRegionHandleTriangleWidthDp.toPx() }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .then(
                    if (editingEnabled) {
                        Modifier.pointerInput(
                            sourceDurationMs,
                            editingEnabled,
                            handleHitHalfWidthPx,
                        ) {
                            awaitEachGesture {
                                var committed = false
                                try {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()

                                    val areaWidthPx = latestWaveformAreaWidthPx
                                    if (areaWidthPx <= 0f) return@awaitEachGesture

                                    if (!latestPersistentViewportZoomed) {
                                        onLoopRegionEditFocusChanged(true)
                                    }

                                    val fingerAreaX = down.position.x
                                    val timelineScaleAtDown = latestTimelineScale
                                    val sourceFitScaleAtDown = latestSourceFitScale
                                    val scaleAtDown =
                                        if (latestPersistentViewportZoomed) {
                                            sourceFitScaleAtDown
                                        } else {
                                            timelineScaleAtDown
                                        }
                                    val clipWidthPxAtDown = scaleAtDown.waveformClipWidthPx(areaWidthPx)
                                    val clipStartPxAtDown = scaleAtDown.waveformClipStartPx(areaWidthPx)

                                    val (dragStartMs, dragEndMs) =
                                        loopRegionDisplayBoundsMs(
                                            isDragging = false,
                                            previewStartMs = previewStartMs,
                                            previewEndMs = previewEndMs,
                                            pendingCommitStartMs = pendingCommitStartMs,
                                            pendingCommitEndMs = pendingCommitEndMs,
                                            loopStartMs = latestLoopStartMs,
                                            loopEndMs = latestLoopEndMs,
                                        )
                                    previewStartMs = dragStartMs
                                    previewEndMs = dragEndMs
                                    pendingCommitStartMs = null
                                    pendingCommitEndMs = null
                                    val leftHandleAreaX =
                                        sourceMsToPointerAreaX(
                                            sourceMs = previewStartMs,
                                            laneScale = scaleAtDown,
                                            waveformAreaWidthPx = areaWidthPx,
                                        )
                                    val rightHandleAreaX =
                                        sourceMsToPointerAreaX(
                                            sourceMs = previewEndMs,
                                            laneScale = scaleAtDown,
                                            waveformAreaWidthPx = areaWidthPx,
                                        )

                                    val begin =
                                        beginLoopRegionDragAtAreaPointer(
                                            areaXPx = fingerAreaX,
                                            clipStartPx = clipStartPxAtDown,
                                            clipWidthPx = clipWidthPxAtDown,
                                            leftHandleAreaX = leftHandleAreaX,
                                            rightHandleAreaX = rightHandleAreaX,
                                            loopStartMs = previewStartMs,
                                            loopEndMs = previewEndMs,
                                            sourceDurationMs = sourceDurationMs,
                                            handleHitHalfWidthPx = handleHitHalfWidthPx,
                                            handleTriangleHalfWidthPx = handleTriangleHalfWidthPx,
                                        )

                                    val sourceFitPointerAtDown =
                                        pointerAreaXToSourceMs(
                                            areaXPx = fingerAreaX,
                                            laneScale = sourceFitScaleAtDown,
                                            waveformAreaWidthPx = areaWidthPx,
                                        )

                                    isDragging = true
                                    previewStartMs = begin.loopStartMs
                                    previewEndMs = begin.loopEndMs

                                    when (begin.activeMode) {
                                        LoopRegionActiveDragMode.LeftHandle -> {
                                            val (start, end) =
                                                applyLoopRegionLeftHandleDrag(
                                                    pointerMs = sourceFitPointerAtDown,
                                                    loopEndMs = begin.anchorEndMs,
                                                    sourceDurationMs = sourceDurationMs,
                                                )
                                            previewStartMs = start
                                            previewEndMs = end
                                        }
                                        LoopRegionActiveDragMode.RightHandle -> {
                                            val (start, end) =
                                                applyLoopRegionRightHandleDrag(
                                                    pointerMs = sourceFitPointerAtDown,
                                                    loopStartMs = begin.anchorStartMs,
                                                    sourceDurationMs = sourceDurationMs,
                                                )
                                            previewStartMs = start
                                            previewEndMs = end
                                        }
                                        LoopRegionActiveDragMode.MoveRegion -> Unit
                                    }
                                    latestOnLoopRegionPreviewChanged(previewStartMs, previewEndMs)

                                    val moveOriginPointerMs =
                                        if (begin.activeMode == LoopRegionActiveDragMode.MoveRegion) {
                                            sourceFitPointerAtDown
                                        } else {
                                            begin.moveOriginPointerMs
                                        }

                                    drag(down.id) { change ->
                                        change.consume()
                                        val pointerMs =
                                            pointerAreaXToSourceMs(
                                                areaXPx = change.position.x,
                                                laneScale = sourceFitScaleAtDown,
                                                waveformAreaWidthPx = areaWidthPx,
                                            )
                                        val (start, end) =
                                            when (begin.activeMode) {
                                                LoopRegionActiveDragMode.LeftHandle ->
                                                    applyLoopRegionLeftHandleDrag(
                                                        pointerMs = pointerMs,
                                                        loopEndMs = begin.anchorEndMs,
                                                        sourceDurationMs = sourceDurationMs,
                                                    )
                                                LoopRegionActiveDragMode.RightHandle ->
                                                    applyLoopRegionRightHandleDrag(
                                                        pointerMs = pointerMs,
                                                        loopStartMs = begin.anchorStartMs,
                                                        sourceDurationMs = sourceDurationMs,
                                                    )
                                                LoopRegionActiveDragMode.MoveRegion ->
                                                    applyLoopRegionMoveDrag(
                                                        deltaMs = pointerMs - moveOriginPointerMs,
                                                        loopStartMs = begin.anchorStartMs,
                                                        loopEndMs = begin.anchorEndMs,
                                                        sourceDurationMs = sourceDurationMs,
                                                    )
                                            }
                                        previewStartMs = start
                                        previewEndMs = end
                                        latestOnLoopRegionPreviewChanged(previewStartMs, previewEndMs)
                                    }

                                    val (commitStart, commitEnd) =
                                        clampLoopRegionMs(
                                            previewStartMs,
                                            previewEndMs,
                                            sourceDurationMs,
                                        )
                                    previewStartMs = commitStart
                                    previewEndMs = commitEnd
                                    pendingCommitStartMs = commitStart
                                    pendingCommitEndMs = commitEnd
                                    onLoopRegionCommit(commitStart, commitEnd)
                                    committed = true
                                } finally {
                                    isDragging = false
                                    if (!latestPersistentViewportZoomed) {
                                        onLoopRegionEditFocusChanged(false)
                                    }
                                    if (!committed) {
                                        previewStartMs = latestLoopStartMs
                                        previewEndMs = latestLoopEndMs
                                        pendingCommitStartMs = null
                                        pendingCommitEndMs = null
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
    ) {
        val areaWidthPx = with(density) { maxWidth.toPx() }
        val bounds =
            loopRegionOverlayAreaBounds(
                loopStartMs = displayStartMs,
                loopEndMs = displayEndMs,
                displayScale = displayScale,
                waveformAreaWidthPx = areaWidthPx,
            )
        val leftLinePx = bounds.startPx
        val rightLinePx = bounds.endPx
        val regionWidthPx =
            (rightLinePx - leftLinePx)
                .coerceAtLeast(with(density) { LoopRegionHandleLineWidthDp.toPx() })
        val regionOffset = with(density) { leftLinePx.toDp() }
        val regionWidth = with(density) { regionWidthPx.toDp() }
        val leftHandleOffset = with(density) { (leftLinePx - handleTriangleWidthPx).toDp() }
        val rightHandleOffset = with(density) { rightLinePx.toDp() }

        Box(
            modifier =
                Modifier
                    .offset(x = regionOffset)
                    .width(regionWidth)
                    .fillMaxHeight()
                    .background(AppColors.Green.copy(alpha = LoopRegionFillAlpha)),
        )
        if (editingEnabled) {
            LoopRegionEdgeHandle(
                side = LoopRegionHandleSide.Start,
                modifier =
                    Modifier
                        .offset(x = leftHandleOffset)
                        .fillMaxHeight(),
            )
            LoopRegionEdgeHandle(
                side = LoopRegionHandleSide.End,
                modifier =
                    Modifier
                        .offset(x = rightHandleOffset)
                        .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun LoopRegionEdgeHandle(
    side: LoopRegionHandleSide,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val lineWidthPx = with(density) { LoopRegionHandleLineWidthDp.toPx() }
    val triangleWidthPx = with(density) { LoopRegionHandleTriangleWidthDp.toPx() }
    val triangleHeightPx = with(density) { LoopRegionHandleTriangleHeightDp.toPx() }
    val handleColor = AppColors.GreenDark
    val lineX =
        when (side) {
            LoopRegionHandleSide.Start -> triangleWidthPx
            LoopRegionHandleSide.End -> 0f
        }

    Canvas(
        modifier =
            modifier
                .width(LoopRegionHandleTriangleWidthDp)
                .fillMaxHeight(),
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val topTriangle =
            Path().apply {
                moveTo(lineX - triangleWidthPx / 2f, 0f)
                lineTo(lineX + triangleWidthPx / 2f, 0f)
                lineTo(lineX, triangleHeightPx)
                close()
            }
        drawPath(topTriangle, color = handleColor)
        val bottomApexY = size.height - triangleHeightPx
        val bottomTriangle =
            Path().apply {
                moveTo(lineX - triangleWidthPx / 2f, size.height)
                lineTo(lineX + triangleWidthPx / 2f, size.height)
                lineTo(lineX, bottomApexY)
                close()
            }
        drawPath(bottomTriangle, color = handleColor)
        drawLine(
            color = handleColor,
            start = Offset(lineX, triangleHeightPx),
            end = Offset(lineX, bottomApexY),
            strokeWidth = lineWidthPx,
        )
    }
}
