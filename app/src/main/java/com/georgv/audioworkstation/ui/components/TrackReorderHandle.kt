package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.georgv.audioworkstation.ui.modifiers.consumeAllPointers
import com.georgv.audioworkstation.ui.theme.Alphas
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

/** Radius (as fraction of the smaller drag-handle dimension) for each indicator dot. */
private const val DragDotRadiusFraction = 0.07f

@Composable
fun TrackReorderHandle(
    trackId: String,
    blockDragHandle: Boolean,
    dragHandleEnabled: Boolean,
    onDragHandleStart: (Offset) -> Unit,
    onDragHandleMove: (Offset) -> Unit,
    onDragHandleEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val isBlocked = blockDragHandle || !dragHandleEnabled

    Box(
        modifier = modifier
            .padding(Dimens.SmallRadius)
            .size(Dimens.DragHandleSize)
            .alpha(if (dragHandleEnabled) 1f else Alphas.HandleIdle)
            .onGloballyPositioned { handleCoords = it }
            .consumeAllPointers(enabled = isBlocked)
            .then(
                if (isBlocked) {
                    Modifier
                } else {
                    Modifier.pointerInput(trackId, dragHandleEnabled) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val positionInRoot = handleCoords?.localToRoot(Offset(offset.x, offset.y))
                                if (positionInRoot != null) onDragHandleStart(positionInRoot)
                            },
                            onDrag = { change, _ ->
                                val positionInRoot = handleCoords?.localToRoot(change.position)
                                if (positionInRoot != null) onDragHandleMove(positionInRoot)
                            },
                            onDragEnd = onDragHandleEnd,
                            onDragCancel = onDragHandleEnd
                        )
                    }
                }
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val color = AppColors.Line.copy(alpha = Alphas.HandleActive)
            val dotR = minOf(size.width, size.height) * DragDotRadiusFraction
            listOf(0.72f to 0.92f, 0.84f to 0.84f, 0.92f to 0.72f).forEach { (tx, ty) ->
                drawCircle(
                    color = color,
                    radius = dotR,
                    center = Offset(size.width * tx, size.height * ty)
                )
            }
        }
    }
}
