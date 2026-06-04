package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.TrackCard
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.theme.Alphas
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

/** Scale for the lifted drag overlay so it reads clearly above the list. */
private const val DragOverlayLiftScale = 1.08f

private const val DragOverlayLiftBorderDp = 2f

private val DragOverlayLiftSpring =
    spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )

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
        popInLift = true,
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
        popInLift = false,
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
    popInLift: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val overlayWidthDp = with(density) { overlayWidthPx.toDp() }
    val overlayHeightDp = with(density) { overlayHeightPx.toDp() }
    val dragShape = RoundedCornerShape(Dimens.TileRadius)
    val liftScaleAnim = remember(track.id, popInLift) { Animatable(1f) }

    LaunchedEffect(track.id, popInLift) {
        if (popInLift) {
            liftScaleAnim.snapTo(1f)
            liftScaleAnim.animateTo(DragOverlayLiftScale, DragOverlayLiftSpring)
        } else {
            liftScaleAnim.snapTo(DragOverlayLiftScale)
        }
    }

    val cardContent =
        remember(
            track.id,
            track.name,
            track.isLoop,
            isSelected,
            isRecording,
            gain,
        ) {
            movableContentOf {
                TrackCard(
                    modifier = Modifier.fillMaxSize(),
                    title = track.name ?: "Track",
                    isSelected = isSelected,
                    isRecording = isRecording,
                    gain = gain,
                    pan = track.pan,
                    onGainChange = null,
                    onPanChange = null,
                    onClick = { },
                    onDelete = { },
                    isLoop = track.isLoop,
                    dragPreview = true,
                )
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
        Box(
            modifier =
                Modifier
                    .size(overlayWidthDp, overlayHeightDp)
                    .scale(liftScaleAnim.value)
                    .shadow(
                        elevation = Dimens.DragOverlayShadow,
                        shape = dragShape,
                        clip = false,
                        spotColor = AppColors.Line.copy(alpha = Alphas.OverlayShadow),
                    )
                    .border(DragOverlayLiftBorderDp.dp, AppColors.Cyan, dragShape)
                    .clip(dragShape),
        ) {
            cardContent()
        }
    }
}
