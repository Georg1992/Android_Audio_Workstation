package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.theme.AppColors
import kotlinx.coroutines.withTimeoutOrNull

private const val LONG_PRESS_MS = 1000L

@Composable
fun TrackCard(
    title: String,
    isSelected: Boolean,
    isRecording: Boolean,
    onClick: () -> Unit,
    onDragHandleLongPress: (positionInRoot: Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        isRecording -> AppColors.Red
        isSelected -> AppColors.Green
        else -> AppColors.Bg
    }

    var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, AppColors.Line, shape)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = AppColors.Line,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            val handleShape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(handleShape)
                    .border(1.dp, AppColors.Line, handleShape)
                    .onGloballyPositioned { handleCoords = it }
                    .pointerInput(handleCoords) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downId = down.id
                            var lastPos = down.position
                            val coords = handleCoords

                            val fingerLiftedBeforeTimeout = withTimeoutOrNull(LONG_PRESS_MS) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.find { it.id == downId }
                                    if (change != null) {
                                        lastPos = change.position
                                        if (!change.pressed) return@withTimeoutOrNull Unit
                                    }
                                    if (event.changes.none { it.pressed }) return@withTimeoutOrNull Unit
                                }
                                Unit
                            } != null

                            if (!fingerLiftedBeforeTimeout && coords != null) {
                                val positionInRoot = coords.localToRoot(lastPos)
                                onDragHandleLongPress(positionInRoot)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Reorder",
                    tint = AppColors.Line
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.Bg)
                .border(1.dp, AppColors.Line, RoundedCornerShape(8.dp))
        )
    }
}
