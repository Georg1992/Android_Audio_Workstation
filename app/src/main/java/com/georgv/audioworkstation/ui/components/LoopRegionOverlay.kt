package com.georgv.audioworkstation.ui.components

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.core.track.applyLoopRegionActiveDrag
import com.georgv.audioworkstation.core.track.beginLoopRegionDragAtPointer
import com.georgv.audioworkstation.core.track.clampLoopRegionMs
import com.georgv.audioworkstation.core.track.loopRegionDisplayBoundsMs
import com.georgv.audioworkstation.core.track.loopRegionOverlayFractions
import com.georgv.audioworkstation.core.track.loopRegionPendingCommitResolved
import com.georgv.audioworkstation.core.track.pointerXToSourceMs
import com.georgv.audioworkstation.ui.theme.AppColors

private val LoopRegionHandleWidthDp = 3.dp
private val LoopRegionHandleHitWidthDp = 32.dp

@Composable
fun LoopRegionEditor(
    sourceDurationMs: Long,
    loopStartMs: Long,
    loopEndMs: Long,
    editingEnabled: Boolean,
    onLoopRegionCommit: (loopStartMs: Long, loopEndMs: Long) -> Unit,
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

    val density = LocalDensity.current
    val handleHitHalfWidthPx = with(density) { LoopRegionHandleHitWidthDp.toPx() / 2f }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .then(
                    if (editingEnabled) {
                        // Keys must stay stable for the whole gesture — never include preview/display bounds.
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

                                    val clipWidthPx = size.width.toFloat()
                                    if (clipWidthPx <= 0f) return@awaitEachGesture

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

                                    val begin =
                                        beginLoopRegionDragAtPointer(
                                            pointerXInClipPx = down.position.x,
                                            clipWidthPx = clipWidthPx,
                                            loopStartMs = previewStartMs,
                                            loopEndMs = previewEndMs,
                                            sourceDurationMs = sourceDurationMs,
                                            handleHitHalfWidthPx = handleHitHalfWidthPx,
                                        )
                                    isDragging = true
                                    previewStartMs = begin.loopStartMs
                                    previewEndMs = begin.loopEndMs

                                    drag(down.id) { change ->
                                        change.consume()
                                        val pointerMs =
                                            pointerXToSourceMs(
                                                pointerXInClipPx = change.position.x,
                                                clipWidthPx = clipWidthPx,
                                                sourceDurationMs = sourceDurationMs,
                                            )
                                        val (start, end) =
                                            applyLoopRegionActiveDrag(
                                                activeMode = begin.activeMode,
                                                pointerMs = pointerMs,
                                                anchorStartMs = begin.anchorStartMs,
                                                anchorEndMs = begin.anchorEndMs,
                                                moveOriginPointerMs = begin.moveOriginPointerMs,
                                                sourceDurationMs = sourceDurationMs,
                                            )
                                        previewStartMs = start
                                        previewEndMs = end
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
        val fractions =
            loopRegionOverlayFractions(
                loopStartMs = displayStartMs,
                loopEndMs = displayEndMs,
                sourceDurationMs = sourceDurationMs,
            )
        val overlayStart = maxWidth * fractions.startFraction
        val overlayWidth = maxWidth * (fractions.endFraction - fractions.startFraction)

        Box(
            modifier =
                Modifier
                    .offset(x = overlayStart)
                    .width(overlayWidth.coerceAtLeast(LoopRegionHandleWidthDp))
                    .fillMaxHeight()
                    .background(AppColors.Yellow.copy(alpha = 0.38f)),
        ) {
            if (editingEnabled) {
                LoopRegionHandle(modifier = Modifier.align(Alignment.CenterStart))
                LoopRegionHandle(modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

@Composable
private fun LoopRegionHandle(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .width(LoopRegionHandleWidthDp)
                .fillMaxHeight()
                .background(AppColors.Line),
    )
}
