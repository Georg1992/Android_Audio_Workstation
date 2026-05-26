@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.georgv.audioworkstation.core.track.hasPersistedPlayableAudio
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.TimelineClip
import com.georgv.audioworkstation.ui.components.TrackCard
import com.georgv.audioworkstation.ui.components.loopRegionEditingEnabled
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.layout.ProjectTrackLayoutSpec

/** Ignore subpixel onGloballyPositioned noise so layout during placement does not rewrite bounds map. */
private const val ItemBoundsEpsilonPx = 1f

private val TrackRowPlacementSpec =
    tween<IntOffset>(durationMillis = 210, easing = FastOutSlowInEasing)

private val PageSliceIncomingSlideTween =
    tween<Float>(durationMillis = 210, easing = FastOutSlowInEasing)

private fun Rect.nearlyEqualsTo(other: Rect, eps: Float): Boolean =
    kotlin.math.abs(left - other.left) < eps &&
        kotlin.math.abs(top - other.top) < eps &&
        kotlin.math.abs(right - other.right) < eps &&
        kotlin.math.abs(bottom - other.bottom) < eps

@Composable
private fun PageSliceBottomIncomingSlide(
    sessionKey: String,
    enabled: Boolean,
    slotDp: Dp,
    spacingDp: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val extraPx = with(density) { (slotDp + spacingDp).toPx() }
    val ty = remember(sessionKey) { Animatable(extraPx) }
    LaunchedEffect(sessionKey, enabled, extraPx) {
        if (!enabled) {
            ty.snapTo(0f)
            return@LaunchedEffect
        }
        ty.snapTo(extraPx)
        ty.animateTo(0f, PageSliceIncomingSlideTween)
    }
    Box(modifier.graphicsLayer { translationY = ty.value }) { content() }
}

@Composable
internal fun LazyItemScope.ProjectTrackListRow(
    track: TrackEntity,
    selectedTrackIds: Set<String>,
    recordingTrackId: String?,
    recordTargetTrackId: String?,
    recordingInputLevel: Float,
    timelineClipsByTrackId: Map<String, TimelineClip>,
    timelineLaneLayoutDurationMs: Long,
    timelineVisibleDurationMs: Long,
    timelinePlayheadPositionMs: Long,
    trackLayout: ProjectTrackLayoutSpec,
    playbackActive: Boolean,
    trackActionsEnabled: Boolean,
    listInteractionLocked: Boolean,
    reorderActive: Boolean,
    dragController: DragController,
    dropSettleInProgress: Boolean,
    dropSettlingTrackId: String?,
    itemBoundsMap: MutableMap<String, Rect>,
    listParentBoundsInRoot: Rect,
    incomingSlideSessionKey: String?,
    isMenuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onDeleteTrack: (String) -> Unit,
    onGainChange: (String, Float) -> Unit,
    onGainCommit: (String, Float) -> Unit,
    onRenameTrack: (String, String) -> Unit,
    onToggleRecordTarget: (String) -> Unit,
    onToggleLoop: (String) -> Unit,
    onUpdateTrackLoopRegion: (String, Long, Long) -> Unit,
    onDragHandleEnd: () -> Unit,
    onDragHandleStarted: (trackId: String) -> Unit,
) {
    @Composable
    fun RowTrackCard() {
        TrackCard(
            title = track.name ?: "Track",
            isSelected = selectedTrackIds.contains(track.id),
            isRecording = recordingTrackId == track.id,
            recordingInputLevel =
                if (recordingTrackId == track.id) {
                    recordingInputLevel
                } else {
                    0f
                },
            timelineClip = timelineClipsByTrackId[track.id],
            laneLayoutDurationMs = timelineLaneLayoutDurationMs,
            globalPlayheadTimelineDurationMs = timelineVisibleDurationMs,
            timelinePlayheadPositionMs = timelinePlayheadPositionMs,
            gain = track.gain,
            onGainChange = { gain -> onGainChange(track.id, gain) },
            onGainCommit = { gain -> onGainCommit(track.id, gain) },
            onClick = { onToggleSelect(track.id) },
            onDelete = { onDeleteTrack(track.id) },
            onRename = { onRenameTrack(track.id, it) },
            onToggleRecordTarget = { onToggleRecordTarget(track.id) },
            isRecordTarget = recordTargetTrackId == track.id,
            recordTargetToggleEnabled = trackActionsEnabled,
            onToggleLoop = { onToggleLoop(track.id) },
            isLoop = track.isLoop,
            loopToggleEnabled = trackActionsEnabled,
            hasPersistedPlayableAudio = track.hasPersistedPlayableAudio(),
            loopRegionEditingEnabled =
                loopRegionEditingEnabled(
                    playbackActive = playbackActive,
                    recordingActive = recordingTrackId != null,
                ),
            onLoopRegionCommit = { startMs, endMs ->
                onUpdateTrackLoopRegion(track.id, startMs, endMs)
            },
            trackActionsEnabled = trackActionsEnabled,
            trackId = track.id,
            trackSlotHeight = trackLayout.trackSlotHeight,
            interactionBlocked = listInteractionLocked,
            blockDragHandle =
                (reorderActive && dragController.draggingKey != track.id) ||
                    dropSettleInProgress,
            dragHandleEnabled = true,
            onDragHandleStart = { positionInRoot ->
                val bounds = itemBoundsMap[track.id] ?: return@TrackCard
                val offsetFromFinger = positionInRoot - Offset(bounds.left, bounds.top)
                val fixedXInParentPx = bounds.left - listParentBoundsInRoot.left
                dragController.start(
                    key = track.id,
                    startPos = positionInRoot,
                    offsetFromFingerToItemTopLeft = offsetFromFinger,
                    fixedXInParentPx = fixedXInParentPx,
                    overlayWidthPx = bounds.right - bounds.left,
                    overlayHeightPx = bounds.bottom - bounds.top,
                )
                onDragHandleStarted(track.id)
            },
            onDragHandleMove = { positionInRoot -> dragController.update(positionInRoot) },
            onDragHandleEnd = onDragHandleEnd,
            isMenuOpen = isMenuOpen,
            onMenuOpen = onMenuOpen,
            onMenuDismiss = onMenuDismiss,
        )
    }

    val isGhostRow =
        (reorderActive && dragController.draggingKey == track.id) ||
            dropSettlingTrackId == track.id

    Box(
        modifier =
            Modifier
                .animateItem(
                    fadeInSpec = null,
                    fadeOutSpec = null,
                    placementSpec =
                        when {
                            incomingSlideSessionKey != null -> null
                            isGhostRow -> null
                            else -> TrackRowPlacementSpec
                        },
                )
                .onGloballyPositioned { coords ->
                    val r = coords.boundsInRoot()
                    val id = track.id
                    val prev = itemBoundsMap[id]
                    if (prev == null || !prev.nearlyEqualsTo(r, ItemBoundsEpsilonPx)) {
                        itemBoundsMap[id] = r
                    }
                }
                .alpha(if (isGhostRow) 0f else 1f),
    ) {
        if (incomingSlideSessionKey != null) {
            PageSliceBottomIncomingSlide(
                sessionKey = incomingSlideSessionKey,
                enabled = true,
                slotDp = trackLayout.trackSlotHeight,
                spacingDp = trackLayout.listVerticalSpacing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RowTrackCard()
            }
        } else {
            RowTrackCard()
        }
    }
}
