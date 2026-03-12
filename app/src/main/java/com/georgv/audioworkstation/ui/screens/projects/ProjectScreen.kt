package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.TrackCard
import com.georgv.audioworkstation.ui.components.TransportPanel
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.drag.reorder.computeReorderDropIndex
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Reorder [list] so that the item with [draggedId] is at [toIndex]. Used for snap-during-drag display. */
private fun reorderForDisplay(list: List<TrackEntity>, draggedId: String, toIndex: Int): List<TrackEntity> {
    val dragged = list.find { it.id == draggedId } ?: return list
    val rest = list.filter { it.id != draggedId }
    val idx = toIndex.coerceIn(0, rest.size)
    return rest.take(idx) + dragged + rest.drop(idx)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    projectId: String,
    quickRecord: Boolean,
    onBack: () -> Unit,
    vm: ProjectViewModel = hiltViewModel()
) {
    LaunchedEffect(projectId, quickRecord) {
        vm.bind(projectId)

        val projectName = if (quickRecord) {
            val time = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            "QuickRec_$time"
        } else {
            "New Project"
        }

        vm.ensureProjectExists(projectId, projectName)

        if (quickRecord) {
            vm.addTrack(projectId)
        }
    }

    val state by vm.uiState.collectAsState()
    val listState = rememberLazyListState()
    val dragController = remember { DragController() }

    val tracks = remember(state.tracks) {
        state.tracks.sortedBy { it.position }
    }

    val displayList: List<TrackEntity> = remember(tracks, dragController.isDragging, dragController.draggingKey, dragController.targetIndex) {
        if (!dragController.isDragging) return@remember tracks
        val key = dragController.draggingKey ?: return@remember tracks
        val targetIdx = dragController.targetIndex ?: return@remember tracks
        reorderForDisplay(tracks, key, targetIdx)
    }

    var listBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var listParentBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    val itemBoundsMap = remember { mutableStateMapOf<String, Rect>() }

    val density = LocalDensity.current
    val reorderThresholdPx = with(density) { Dimens.ReorderIndexThreshold.toPx() }

    LaunchedEffect(dragController.fingerPos, listState, listBoundsInRoot, displayList, reorderThresholdPx) {
        if (!dragController.isDragging) return@LaunchedEffect
        val key = dragController.draggingKey ?: return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val contentTop = listBoundsInRoot.top - layoutInfo.viewportStartOffset
        val listLocalY = dragController.fingerPos.y - contentTop
        val candidateIndex = computeReorderDropIndex(
            listState = listState,
            draggedKey = key,
            draggedCenterY = listLocalY,
            tracksStartIndex = 0,
            itemsCount = displayList.size
        )
        val currentIndex = dragController.targetIndex ?: 0
        if (candidateIndex == currentIndex) {
            dragController.targetIndex = candidateIndex
            return@LaunchedEffect
        }
        val visible = layoutInfo.visibleItemsInfo.filter { it.index >= 0 && it.index < displayList.size }
        val currentSlot = visible.find { it.index == currentIndex }
        if (currentSlot == null) {
            dragController.targetIndex = candidateIndex
            return@LaunchedEffect
        }
        val slotTop = currentSlot.offset.toFloat()
        val slotBottom = currentSlot.offset + currentSlot.size
        val pastThreshold = when {
            candidateIndex > currentIndex -> listLocalY >= slotBottom + reorderThresholdPx
            candidateIndex < currentIndex -> listLocalY <= slotTop - reorderThresholdPx
            else -> true
        }
        if (pastThreshold) dragController.targetIndex = candidateIndex
    }

    ScreenScaffold(title = state.project?.name ?: "Project", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val panelShape = RoundedCornerShape(Dimens.TileRadius)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding)
                    .height(Dimens.PanelPlaceholderHeight)
                    .clip(panelShape)
                    .background(AppColors.Bg)
                    .border(Dimens.Stroke, AppColors.Line, panelShape)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        listParentBoundsInRoot = coords.boundsInRoot()
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
                    verticalArrangement = Arrangement.spacedBy(Dimens.Gap)
                ) {
                    items(
                        items = displayList,
                        key = { it.id }
                    ) { track ->
                        val isDragging = dragController.isDragging && dragController.draggingKey == track.id

                        var gainLocal by remember(track.id) { mutableFloatStateOf(track.gain) }

                        Box(
                            modifier = Modifier
                                .onGloballyPositioned { coords ->
                                    itemBoundsMap[track.id] = coords.boundsInRoot()
                                }
                                .alpha(if (isDragging) 0f else 1f)
                        ) {
                            TrackCard(
                                title = track.name ?: "Track",
                                isSelected = state.selectedTrackIds.contains(track.id),
                                isRecording = state.recordingTrackId == track.id,
                                gain = gainLocal,
                                onGainChange = { gainLocal = it },
                                onClick = { vm.toggleSelect(track.id) },
                                onDelete = { vm.deleteTrack(track.id) },
                                trackId = track.id,
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
                                        overlayHeightPx = bounds.bottom - bounds.top
                                    )
                                    dragController.targetIndex = tracks.indexOfFirst { it.id == track.id }
                                },
                                onDragHandleMove = { positionInRoot ->
                                    dragController.update(positionInRoot)
                                },
                                onDragHandleEnd = {
                                    val toIndex = dragController.targetIndex ?: 0
                                    val key = dragController.end()
                                    if (key != null) vm.moveTrack(projectId, key, toIndex)
                                }
                            )
                        }
                    }
                }

                if (dragController.isDragging) {
                    val draggedKey = dragController.draggingKey
                    val draggedTrack = draggedKey?.let { id -> tracks.find { it.id == id } }
                    if (draggedTrack != null) {
                        val overlayYInParentPx = dragController.fingerPos.y - dragController.dragOffset.y - listParentBoundsInRoot.top
                        var overlayCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
                        val density = LocalDensity.current
                        val overlayWidthDp = with(density) { dragController.overlayWidthPx.toDp() }
                        val overlayHeightDp = with(density) { dragController.overlayHeightPx.toDp() }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset(
                                    x = with(density) { dragController.fixedXInParentPx.toDp() },
                                    y = with(density) { overlayYInParentPx.toDp() }
                                )
                                .onGloballyPositioned { overlayCoords = it }
                                .pointerInput(draggedKey) {
                                    awaitEachGesture {
                                        do {
                                            val event = awaitPointerEvent()
                                            val coords = overlayCoords
                                            when (event.type) {
                                                PointerEventType.Move -> {
                                                    event.changes.firstOrNull()?.let { change ->
                                                        if (coords != null) {
                                                            val rootPos = coords.localToRoot(change.position)
                                                            dragController.update(rootPos)
                                                        }
                                                    }
                                                }
                                                PointerEventType.Release -> {
                                                    val toIndex = dragController.targetIndex ?: 0
                                                    val key = dragController.end()
                                                    if (key != null) vm.moveTrack(projectId, key, toIndex)
                                                    return@awaitEachGesture
                                                }
                                                else -> {}
                                            }
                                        } while (true)
                                    }
                                }
                        ) {
                            var gainLocal by remember(draggedTrack.id) { mutableFloatStateOf(draggedTrack.gain) }
                            val dragShape = RoundedCornerShape(Dimens.TileRadius)
                            Box(
                                modifier = Modifier
                                    .size(overlayWidthDp, overlayHeightDp)
                                    .scale(1.05f)
                                    .shadow(24.dp, dragShape, clip = false, spotColor = AppColors.Line.copy(alpha = 0.6f))
                                    .border(2.dp, AppColors.Line, dragShape)
                                    .clip(dragShape)
                            ) {
                                TrackCard(
                                    title = draggedTrack.name ?: "Track",
                                    isSelected = state.selectedTrackIds.contains(draggedTrack.id),
                                    isRecording = state.recordingTrackId == draggedTrack.id,
                                    gain = gainLocal,
                                    onGainChange = { gainLocal = it },
                                    onClick = { },
                                    onDelete = { }
                                )
                            }
                        }
                    }
                }
            }

            TransportPanel(
                isRecording = state.recordingTrackId != null,
                isPlaying = state.playingTrackIds.isNotEmpty(),
                isPlayEnabled = state.isPlayEnabled,
                isStopEnabled = state.isStopEnabled,
                onPlay = { vm.onPlayPressed() },
                onStop = { vm.onStopPressed() },
                onRecord = { vm.onRecordPressed(projectId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding)
            )
        }
    }
}
