package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.TrackCard
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun ProjectTrackList(
    tracks: List<TrackEntity>,
    selectedTrackIds: Set<String>,
    recordingTrackId: String?,
    listState: LazyListState,
    dragController: DragController,
    onToggleSelect: (String) -> Unit,
    onDeleteTrack: (String) -> Unit,
    onGainChange: (String, Float) -> Unit,
    onGainCommit: (String, Float) -> Unit,
    onRenameTrack: (String, String) -> Unit,
    onToggleLoop: (String) -> Unit,
    onReorderTracks: (List<TrackEntity>) -> Unit,
    onPersistTrackOrder: () -> Unit,
    modifier: Modifier = Modifier
) {
    var listBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var listParentBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var dragHostCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val itemBoundsMap = remember { mutableStateMapOf<String, Rect>() }
    var dropSettle by remember { mutableStateOf<DropSettleSnap?>(null) }
    var nextSettleUid by remember { mutableLongStateOf(1L) }

    val currentTracks by rememberUpdatedState(tracks)
    val latestOnReorderTracks by rememberUpdatedState(onReorderTracks)

    LaunchedEffect(dragController.draggingKey, listBoundsInRoot) {
        if (dragController.draggingKey == null) return@LaunchedEffect
        snapshotFlow { dragController.fingerPos }.collect {
            if (!dragController.isDragging) return@collect
            maybeNeighborSwap(currentTracks, dragController, listState, listBoundsInRoot)
                ?.let(latestOnReorderTracks)
        }
    }

    fun completeDrop() {
        val key = dragController.draggingKey ?: return
        val trackEntity = tracks.find { it.id == key }
        val bounds = itemBoundsMap[key]
        val parentTop = listParentBoundsInRoot.top

        fun finishImmediate() {
            onPersistTrackOrder()
            dragController.end()
        }

        if (trackEntity == null || bounds == null) {
            finishImmediate()
            return
        }

        val startY = dragController.fingerPos.y - dragController.dragOffset.y - parentTop
        val targetY = bounds.top - parentTop
        val wPx = dragController.overlayWidthPx
        val hPx = dragController.overlayHeightPx

        if (!startY.isFinite() || !targetY.isFinite() ||
            wPx <= 0f || hPx <= 0f ||
            bounds.isEmpty ||
            !bounds.top.isFinite() ||
            !bounds.left.isFinite()
        ) {
            finishImmediate()
            return
        }

        dropSettle = DropSettleSnap(
            settleUid = nextSettleUid++,
            trackId = key,
            track = trackEntity,
            isSelected = selectedTrackIds.contains(key),
            isRecording = recordingTrackId == key,
            gain = trackEntity.gain,
            fixedXInParentPx = dragController.fixedXInParentPx,
            overlayWidthPx = wPx,
            overlayHeightPx = hPx,
            startTranslationYPx = startY,
            targetTranslationYPx = targetY,
        )
        onPersistTrackOrder()
        dragController.end()
    }

    val latestCompleteDrop by rememberUpdatedState(::completeDrop)
    val listInteractionLocked = dragController.isDragging || dropSettle != null
    val reorderActive = dragController.isDragging

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                listParentBoundsInRoot = coords.boundsInRoot()
                dragHostCoords = coords
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        if (dragController.isDragging) {
                            val pressed = event.changes.firstOrNull { it.pressed }
                            val coords = dragHostCoords
                            if (pressed != null && coords != null) {
                                dragController.update(coords.localToRoot(pressed.position))
                                pressed.consume()
                            }
                            if (event.changes.none { it.pressed }) {
                                latestCompleteDrop()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.TileInnerPadding)
                .onGloballyPositioned { coords ->
                    listBoundsInRoot = coords.boundsInRoot()
                },
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
            userScrollEnabled = !listInteractionLocked
        ) {
            itemsIndexed(items = tracks, key = { _, track -> track.id }) { index, track ->
                val settlingId = dropSettle?.trackId
                val isGhostRow =
                    (reorderActive && dragController.draggingKey == track.id) ||
                        (settlingId == track.id)
                val rowFullyVisible = isTrackFullyVisibleInLazyList(listState, index)

                Box(
                    modifier = Modifier
                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                        .onGloballyPositioned { coords ->
                            itemBoundsMap[track.id] = coords.boundsInRoot()
                        }
                        .alpha(if (isGhostRow) 0f else 1f)
                ) {
                    TrackCard(
                        title = track.name ?: "Track",
                        isSelected = selectedTrackIds.contains(track.id),
                        isRecording = recordingTrackId == track.id,
                        gain = track.gain,
                        onGainChange = { gain -> onGainChange(track.id, gain) },
                        onGainCommit = { gain -> onGainCommit(track.id, gain) },
                        onClick = { onToggleSelect(track.id) },
                        onDelete = { onDeleteTrack(track.id) },
                        onRename = { onRenameTrack(track.id, it) },
                        onToggleLoop = { onToggleLoop(track.id) },
                        isLoop = track.isLoop,
                        trackId = track.id,
                        interactionBlocked = listInteractionLocked,
                        blockDragHandle =
                            (reorderActive && dragController.draggingKey != track.id) ||
                                dropSettle != null,
                        dragHandleEnabled = rowFullyVisible,
                        onDragHandleStart = { positionInRoot ->
                            if (!rowFullyVisible) return@TrackCard
                            val bounds = itemBoundsMap[track.id] ?: return@TrackCard
                            val offsetFromFinger = positionInRoot - Offset(bounds.left, bounds.top)
                            val fixedXInParentPx = bounds.left - listParentBoundsInRoot.left
                            dragController.start(
                                key = track.id,
                                startPos = positionInRoot,
                                offsetFromFingerToItemTopLeft = offsetFromFinger,
                                fixedXInParentPx = fixedXInParentPx,
                                overlayWidthPx = bounds.right - bounds.left,
                                overlayHeightPx = bounds.bottom - bounds.top
                            )
                        },
                        onDragHandleMove = { positionInRoot ->
                            dragController.update(positionInRoot)
                        },
                        onDragHandleEnd = { }
                    )
                }
            }
        }

        val settleSnap = dropSettle
        if (dragController.isDragging) {
            val draggedTrack = dragController.draggingKey?.let { draggedId ->
                tracks.find { it.id == draggedId }
            }
            if (draggedTrack != null) {
                TrackDragOverlay(
                    track = draggedTrack,
                    isSelected = selectedTrackIds.contains(draggedTrack.id),
                    isRecording = recordingTrackId == draggedTrack.id,
                    gain = draggedTrack.gain,
                    dragController = dragController,
                    parentTopInRootPx = listParentBoundsInRoot.top,
                )
            }
        } else if (settleSnap != null) {
            val settleYAnim = remember(settleSnap.settleUid) {
                Animatable(settleSnap.startTranslationYPx)
            }
            LaunchedEffect(settleSnap.settleUid) {
                settleYAnim.snapTo(settleSnap.startTranslationYPx)
                settleYAnim.animateTo(
                    settleSnap.targetTranslationYPx,
                    animationSpec =
                        tween(
                            durationMillis = DropSettleDurationMs,
                            easing = FastOutSlowInEasing,
                        ),
                )
                if (dropSettle?.settleUid == settleSnap.settleUid) {
                    dropSettle = null
                }
            }
            TrackDragSettlingOverlay(
                track = settleSnap.track,
                isSelected = settleSnap.isSelected,
                isRecording = settleSnap.isRecording,
                gain = settleSnap.gain,
                translationXInParentPx = settleSnap.fixedXInParentPx,
                translationYInParentPx = settleYAnim.value,
                overlayWidthPx = settleSnap.overlayWidthPx,
                overlayHeightPx = settleSnap.overlayHeightPx,
            )
        }
    }
}

private data class DropSettleSnap(
    val settleUid: Long,
    val trackId: String,
    val track: TrackEntity,
    val isSelected: Boolean,
    val isRecording: Boolean,
    val gain: Float,
    val fixedXInParentPx: Float,
    val overlayWidthPx: Float,
    val overlayHeightPx: Float,
    val startTranslationYPx: Float,
    val targetTranslationYPx: Float,
)

private const val DropSettleDurationMs = 150
