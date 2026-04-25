package com.georgv.audioworkstation.ui.screens.projects

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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.gestures.awaitEachGesture

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

    val currentTracks by rememberUpdatedState(tracks)
    val latestOnReorderTracks by rememberUpdatedState(onReorderTracks)

    LaunchedEffect(
        dragController.fingerPos,
        dragController.draggingKey,
        listBoundsInRoot
    ) {
        if (!dragController.isDragging) return@LaunchedEffect
        maybeNeighborSwap(currentTracks, dragController, listState, listBoundsInRoot)
            ?.let(latestOnReorderTracks)
    }

    fun completeDrop() {
        if (dragController.draggingKey == null) return
        onPersistTrackOrder()
        dragController.end()
    }

    val latestCompleteDrop by rememberUpdatedState(::completeDrop)
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
            userScrollEnabled = !reorderActive
        ) {
            itemsIndexed(items = tracks) { index, track ->
                val isDragging = reorderActive && dragController.draggingKey == track.id
                val rowFullyVisible = isTrackFullyVisibleInLazyList(listState, index)

                Box(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            itemBoundsMap[track.id] = coords.boundsInRoot()
                        }
                        .alpha(if (isDragging) 0f else 1f)
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
                        interactionBlocked = reorderActive,
                        blockDragHandle = reorderActive && dragController.draggingKey != track.id,
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
                    onGainChange = { gain -> onGainChange(draggedTrack.id, gain) }
                )
            }
        }
    }
}
