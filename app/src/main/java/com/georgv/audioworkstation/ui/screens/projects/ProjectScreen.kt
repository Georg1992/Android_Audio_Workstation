package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.TrackCard
import com.georgv.audioworkstation.ui.components.TransportPanel
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlinx.coroutines.yield
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    val state by vm.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val dragController = remember { DragController() }

    val tracks = state.tracks

    val sessionGainByTrackId = remember { mutableStateMapOf<String, Float>() }
    LaunchedEffect(projectId) {
        sessionGainByTrackId.clear()
    }

    LaunchedEffect(state.recordingTrackId, tracks.size) {
        val id = state.recordingTrackId ?: return@LaunchedEffect
        yield()
        val index = vm.uiState.value.tracks.indexOfFirst { it.id == id }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
    }

    var listBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var listParentBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var dragHostCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val itemBoundsMap = remember { mutableStateMapOf<String, Rect>() }

    LaunchedEffect(
        dragController.fingerPos,
        dragController.draggingKey,
        listBoundsInRoot
    ) {
        if (!dragController.isDragging) return@LaunchedEffect
        checkNeighborSwap(vm, projectId, tracks, dragController, listState, listBoundsInRoot)
    }

    fun completeDrop() {
        if (dragController.draggingKey == null) return
        vm.persistTrackOrderToDb(projectId)
        dragController.end()
    }

    val latestCompleteDrop by rememberUpdatedState(::completeDrop)

    val reorderActive = dragController.isDragging
    val density = LocalDensity.current

    ScreenScaffold(
        title = state.project?.name ?: stringResource(R.string.screen_project),
        onBack = if (reorderActive) null else onBack
    ) { padding ->
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
                    .then(
                        if (reorderActive) {
                            Modifier.pointerInput(Unit) {
                                while (true) {
                                    awaitEachGesture {
                                        do {
                                            val event = awaitPointerEvent()
                                            event.changes.forEach { it.consume() }
                                        } while (event.changes.any { it.pressed })
                                    }
                                }
                            }
                        } else {
                            Modifier
                        }
                    )
            )

            Box(
                modifier = Modifier
                    .weight(1f)
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
                    itemsIndexed(
                        items = tracks
                    ) { index, track ->
                        val isDragging = dragController.isDragging && dragController.draggingKey == track.id

                        val displayedGain = sessionGainByTrackId[track.id] ?: track.gain
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
                                isSelected = state.selectedTrackIds.contains(track.id),
                                isRecording = state.recordingTrackId == track.id,
                                gain = displayedGain,
                                onGainChange = { sessionGainByTrackId[track.id] = it },
                                onClick = { vm.toggleSelect(track.id) },
                                onDelete = { vm.deleteTrack(track.id) },
                                onRename = { vm.renameTrack(track.id, it) },
                                trackId = track.id,
                                interactionBlocked = reorderActive,
                                blockDragHandle = reorderActive && dragController.draggingKey != track.id,
                                dragHandleEnabled = rowFullyVisible,
                                onDragHandleStart = { positionInRoot ->
                                    if (!isTrackFullyVisibleInLazyList(listState, index)) return@TrackCard
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
                    val draggedKey = dragController.draggingKey
                    val draggedTrack = draggedKey?.let { id -> tracks.find { it.id == id } }
                    if (draggedTrack != null) {
                        val overlayGain = sessionGainByTrackId[draggedTrack.id] ?: draggedTrack.gain
                        val overlayYInParentPx = dragController.fingerPos.y - dragController.dragOffset.y - listParentBoundsInRoot.top
                        val overlayWidthDp = with(density) { dragController.overlayWidthPx.toDp() }
                        val overlayHeightDp = with(density) { dragController.overlayHeightPx.toDp() }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset(
                                    x = with(density) { dragController.fixedXInParentPx.toDp() },
                                    y = with(density) { overlayYInParentPx.toDp() }
                                )
                        ) {
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
                                    gain = overlayGain,
                                    onGainChange = { sessionGainByTrackId[draggedTrack.id] = it },
                                    onClick = { },
                                    onDelete = { },
                                    interactionBlocked = true
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
                inputLocked = reorderActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding)
            )
        }
    }
}

/** Reorders live by checking only the dragged item's immediate neighbors. */
private fun checkNeighborSwap(
    vm: ProjectViewModel,
    projectId: String,
    tracks: List<TrackEntity>,
    dragController: DragController,
    listState: LazyListState,
    listBoundsInRoot: Rect
) {
    val key = dragController.draggingKey ?: return
    val currentIndex = tracks.indexOfFirst { it.id == key }
    if (currentIndex < 0) return

    val layoutInfo = listState.layoutInfo
    val draggedCenterY = fingerYInListSpace(
        dragController.fingerPos.y,
        listBoundsInRoot,
        layoutInfo.viewportStartOffset
    ) - dragController.dragOffset.y + dragController.overlayHeightPx / 2f

    val visible = layoutInfo.visibleItemsInfo

    val neighborBelow = visible.find { it.index == currentIndex + 1 }
    if (shouldSwapWithNext(draggedCenterY, neighborBelow)) {
        vm.setTrackOrderSession(projectId, swapInList(tracks, currentIndex, currentIndex + 1))
        return
    }

    val neighborAbove = visible.find { it.index == currentIndex - 1 }
    if (shouldSwapWithPrevious(draggedCenterY, neighborAbove)) {
        vm.setTrackOrderSession(projectId, swapInList(tracks, currentIndex, currentIndex - 1))
    }
}

/** Moves the item at [fromIndex] to [toIndex] by remove + insert (adjacent swap for reorder). */
private fun swapInList(tracks: List<TrackEntity>, fromIndex: Int, toIndex: Int): List<TrackEntity> {
    return tracks.toMutableList().also {
        val item = it.removeAt(fromIndex)
        it.add(toIndex, item)
    }
}

private fun shouldSwapWithNext(
    draggedCenterY: Float,
    neighbor: LazyListItemInfo?
): Boolean = neighbor != null && draggedCenterY > neighbor.offset + neighbor.size / 2f

private fun shouldSwapWithPrevious(
    draggedCenterY: Float,
    neighbor: LazyListItemInfo?
): Boolean = neighbor != null && draggedCenterY < neighbor.offset + neighbor.size / 2f

private fun isTrackFullyVisibleInLazyList(listState: LazyListState, itemIndex: Int): Boolean {
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.find { it.index == itemIndex } ?: return false
    return item.offset >= info.viewportStartOffset &&
        item.offset + item.size <= info.viewportEndOffset
}

/** Converts a root-space Y coordinate to the LazyList's content coordinate space. */
private fun fingerYInListSpace(fingerRootY: Float, listBoundsInRoot: Rect, viewportStartOffset: Int): Float =
    fingerRootY - (listBoundsInRoot.top - viewportStartOffset)
