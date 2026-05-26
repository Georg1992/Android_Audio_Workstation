package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.georgv.audioworkstation.ui.modifiers.consumeAllPointers
import com.georgv.audioworkstation.ui.theme.Alphas
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

private val DragHandleDotCenters =
    listOf(
        0.72f to 0.92f,
        0.84f to 0.84f,
        0.92f to 0.72f,
    )

/** Radius (as fraction of the smaller drag-handle dimension) for each indicator dot. */
private const val DragDotRadiusFraction = 0.07f

/** Bottom-right triangle — matches dot layout; clips hit-testing so the fader keeps the rest. */
private object ReorderHandleHitShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path =
            Path().apply {
                moveTo(0f, size.height)
                lineTo(size.width, size.height)
                lineTo(size.width, 0f)
                close()
            }
        return Outline.Generic(path)
    }
}

@Composable
fun TrackReorderHandle(
    trackId: String,
    blockDragHandle: Boolean,
    dragHandleEnabled: Boolean,
    onDragHandleStart: (Offset) -> Unit,
    onDragHandleMove: (Offset) -> Unit,
    onDragHandleEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestCoords by rememberUpdatedState(handleCoords)
    val latestOnStart by rememberUpdatedState(onDragHandleStart)
    val latestOnMove by rememberUpdatedState(onDragHandleMove)
    val latestOnEnd by rememberUpdatedState(onDragHandleEnd)
    val isBlocked = blockDragHandle || !dragHandleEnabled

    Box(
        modifier =
            modifier
                .padding(Dimens.SmallRadius)
                .size(Dimens.DragHandleTouchTargetSize),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Box(
            modifier =
                Modifier
                    .size(Dimens.DragHandleTouchTargetSize)
                    .alpha(if (dragHandleEnabled) 1f else Alphas.HandleIdle)
                    .clip(ReorderHandleHitShape)
                    .onGloballyPositioned { handleCoords = it }
                    .consumeAllPointers(enabled = isBlocked)
                    .then(
                        if (isBlocked) {
                            Modifier
                        } else {
                            Modifier.pointerInput(trackId, dragHandleEnabled) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val coords = latestCoords ?: return@detectDragGestures
                                        latestOnStart(coords.localToRoot(offset))
                                    },
                                    onDrag = { change, _ ->
                                        val coords = latestCoords ?: return@detectDragGestures
                                        change.consume()
                                        latestOnMove(coords.localToRoot(change.position))
                                    },
                                    onDragEnd = { latestOnEnd() },
                                    onDragCancel = { latestOnEnd() },
                                )
                            }
                        },
                    ),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Canvas(Modifier.size(Dimens.DragHandleSize)) {
                val color = AppColors.Line.copy(alpha = Alphas.HandleActive)
                val dotR = minOf(size.width, size.height) * DragDotRadiusFraction
                DragHandleDotCenters.forEach { (tx, ty) ->
                    drawCircle(
                        color = color,
                        radius = dotR,
                        center = Offset(size.width * tx, size.height * ty),
                    )
                }
            }
        }
    }
}
