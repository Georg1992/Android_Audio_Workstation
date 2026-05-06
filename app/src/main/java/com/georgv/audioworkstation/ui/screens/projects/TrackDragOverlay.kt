package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.TrackCard
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.theme.Alphas
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

/** Slight scale so the overlay reads as lifted without drifting from list row size. */
private const val DragOverlayLiftScale = 1.02f

@Composable
fun TrackDragOverlay(
    track: TrackEntity,
    isSelected: Boolean,
    isRecording: Boolean,
    gain: Float,
    dragController: DragController,
    parentTopInRootPx: Float,
    parentHeightPx: Float,
    modifier: Modifier = Modifier
) {
    val rawTranslationY =
        dragController.fingerPos.y - dragController.dragOffset.y - parentTopInRootPx
    val maxTranslationY =
        (parentHeightPx - dragController.overlayHeightPx).coerceAtLeast(0f)
    val translationY = rawTranslationY.coerceIn(0f, maxTranslationY)
    TrackDragFloatingCard(
        track = track,
        isSelected = isSelected,
        isRecording = isRecording,
        gain = gain,
        translationXInParentPx = dragController.fixedXInParentPx,
        translationYInParentPx = translationY,
        overlayWidthPx = dragController.overlayWidthPx,
        overlayHeightPx = dragController.overlayHeightPx,
        modifier = modifier,
    )
}

@Composable
fun TrackDragSettlingOverlay(
    track: TrackEntity,
    isSelected: Boolean,
    isRecording: Boolean,
    gain: Float,
    translationXInParentPx: Float,
    translationYInParentPx: Float,
    overlayWidthPx: Float,
    overlayHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    TrackDragFloatingCard(
        track = track,
        isSelected = isSelected,
        isRecording = isRecording,
        gain = gain,
        translationXInParentPx = translationXInParentPx,
        translationYInParentPx = translationYInParentPx,
        overlayWidthPx = overlayWidthPx,
        overlayHeightPx = overlayHeightPx,
        modifier = modifier,
    )
}

@Composable
private fun TrackDragFloatingCard(
    track: TrackEntity,
    isSelected: Boolean,
    isRecording: Boolean,
    gain: Float,
    translationXInParentPx: Float,
    translationYInParentPx: Float,
    overlayWidthPx: Float,
    overlayHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val overlayWidthDp = with(density) { overlayWidthPx.toDp() }
    val overlayHeightDp = with(density) { overlayHeightPx.toDp() }
    val dragShape = RoundedCornerShape(Dimens.TileRadius)

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = translationXInParentPx
                translationY = translationYInParentPx
            }
    ) {
        Box(
            modifier = Modifier
                .size(overlayWidthDp, overlayHeightDp)
                .scale(DragOverlayLiftScale)
                .border(Dimens.Stroke, AppColors.Line, dragShape)
                .shadow(
                    elevation = Dimens.DragOverlayShadow,
                    shape = dragShape,
                    clip = false,
                    spotColor = AppColors.Line.copy(alpha = Alphas.OverlayShadow)
                )
                .clip(dragShape)
        ) {
            TrackCard(
                modifier = Modifier.fillMaxSize(),
                title = track.name ?: "Track",
                isSelected = isSelected,
                isRecording = isRecording,
                gain = gain,
                onGainChange = null,
                onClick = { },
                onDelete = { },
                isLoop = track.isLoop,
                dragPreview = true,
            )
        }
    }
}
