package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
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

/** Read [DragController] translation only inside [Modifier.graphicsLayer] to avoid recomposing the card subtree each MOVE. */
private data class OverlayLiveDrag(
    val dragController: DragController,
    val parentTopInRootPx: Float,
    val parentHeightPx: Float,
)

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
    val liveDrag =
        remember(dragController, parentTopInRootPx, parentHeightPx) {
            OverlayLiveDrag(dragController, parentTopInRootPx, parentHeightPx)
        }
    TrackDragFloatingCard(
        track = track,
        isSelected = isSelected,
        isRecording = isRecording,
        gain = gain,
        overlayWidthPx = dragController.overlayWidthPx,
        overlayHeightPx = dragController.overlayHeightPx,
        liveDrag = liveDrag,
        translationXInParentPx = 0f,
        translationYInParentPx = 0f,
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
        overlayWidthPx = overlayWidthPx,
        overlayHeightPx = overlayHeightPx,
        liveDrag = null,
        translationXInParentPx = translationXInParentPx,
        translationYInParentPx = translationYInParentPx,
        modifier = modifier,
    )
}

@Composable
private fun TrackDragFloatingCard(
    track: TrackEntity,
    isSelected: Boolean,
    isRecording: Boolean,
    gain: Float,
    overlayWidthPx: Float,
    overlayHeightPx: Float,
    liveDrag: OverlayLiveDrag?,
    translationXInParentPx: Float,
    translationYInParentPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val overlayWidthDp = with(density) { overlayWidthPx.toDp() }
    val overlayHeightDp = with(density) { overlayHeightPx.toDp() }
    val dragShape = RoundedCornerShape(Dimens.TileRadius)

    val cardStack =
        remember(
            density,
            track.id,
            track.name,
            track.isLoop,
            isSelected,
            isRecording,
            gain,
            overlayWidthPx,
            overlayHeightPx,
        ) {
            movableContentOf {
                Box(
                    modifier =
                        Modifier
                            .size(overlayWidthDp, overlayHeightDp)
                            .scale(DragOverlayLiftScale)
                            .border(Dimens.Stroke, AppColors.Line, dragShape)
                            .shadow(
                                elevation = Dimens.DragOverlayShadow,
                                shape = dragShape,
                                clip = false,
                                spotColor = AppColors.Line.copy(alpha = Alphas.OverlayShadow),
                            )
                            .clip(dragShape),
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

    val layerModifier =
        if (liveDrag != null) {
            val ld = liveDrag
            Modifier.graphicsLayer {
                val dc = ld.dragController
                translationX = dc.fixedXInParentPx
                val maxY =
                    (ld.parentHeightPx - dc.overlayHeightPx).coerceAtLeast(0f)
                val rawY = dc.fingerY - dc.dragOffset.y - ld.parentTopInRootPx
                translationY = rawY.coerceIn(0f, maxY)
            }
        } else {
            Modifier.graphicsLayer {
                translationX = translationXInParentPx
                translationY = translationYInParentPx
            }
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .then(layerModifier),
    ) {
        cardStack()
    }
}
