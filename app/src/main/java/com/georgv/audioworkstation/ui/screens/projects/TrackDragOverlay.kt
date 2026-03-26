package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.TrackCard
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun TrackDragOverlay(
    track: TrackEntity,
    isSelected: Boolean,
    isRecording: Boolean,
    gain: Float,
    dragController: DragController,
    parentTopInRootPx: Float,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val overlayYInParentPx = dragController.fingerPos.y - dragController.dragOffset.y - parentTopInRootPx
    val overlayWidthDp = with(density) { dragController.overlayWidthPx.toDp() }
    val overlayHeightDp = with(density) { dragController.overlayHeightPx.toDp() }
    val dragShape = RoundedCornerShape(Dimens.TileRadius)

    Box(
        modifier = modifier
            .fillMaxSize()
            .offset(
                x = with(density) { dragController.fixedXInParentPx.toDp() },
                y = with(density) { overlayYInParentPx.toDp() }
            )
    ) {
        Box(
            modifier = Modifier
                .size(overlayWidthDp, overlayHeightDp)
                .scale(1.05f)
                .shadow(24.dp, dragShape, clip = false, spotColor = AppColors.Line.copy(alpha = 0.6f))
                .border(2.dp, AppColors.Line, dragShape)
                .clip(dragShape)
        ) {
            TrackCard(
                title = track.name ?: "Track",
                isSelected = isSelected,
                isRecording = isRecording,
                gain = gain,
                onGainChange = onGainChange,
                onClick = { },
                onDelete = { },
                interactionBlocked = true
            )
        }
    }
}
