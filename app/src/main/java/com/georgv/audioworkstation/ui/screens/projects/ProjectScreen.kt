package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
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
import kotlin.math.abs

private fun fingerYInListSpace(fingerRootY: Float, listBoundsInRoot: Rect, viewportStartOffset: Int): Float =
    fingerRootY - (listBoundsInRoot.top - viewportStartOffset)

private fun isTrackFullyVisibleInLazyList(listState: LazyListState, itemIndex: Int): Boolean {
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.find { it.index == itemIndex } ?: return false
    return item.offset >= info.viewportStartOffset &&
        item.offset + item.size <= info.viewportEndOffset
}

/** New list order if [draggedId] is placed at [toIndex] (0..n-1 in list with dragged removed). */
private fun reorderForDisplay(list: List<TrackEntity>, draggedId: String, toIndex: Int): List<TrackEntity> {
    val dragged = list.find { it.id == draggedId } ?: return list
    val rest = list.filter { it.id != draggedId }
    val idx = toIndex.coerceIn(0, rest.size)
    return rest.take(idx) + dragged + rest.drop(idx)
}

/** When threshold crossed, updates session order only (no DB). */
private fun applyReorderTargetWithThreshold(
    vm: ProjectViewModel,
    projectId: String,
    tracks: List<TrackEntity>,
    dragController: DragController,
    listState: LazyListState,
    listBoundsInRoot: Rect,
    reorderThresholdPx: Float,
    dragStartDeadzonePx: Float,
    reorderCenterMoveGatePx: Float
) {
    if (!dragController.isDragging) return
    val key = dragController.draggingKey ?: return
    if (abs(dragController.fingerPos.y - dragController.dragAnchorYRoot) < dragStartDeadzonePx) return
    val layoutInfo = listState.layoutInfo
    val fingerListLocalY = fingerYInListSpace(dragController.fingerPos.y, listBoundsInRoot, layoutInfo.viewportStartOffset)
    val centerListLocalY = fingerListLocalY - dragController.dragOffset.y + dragController.overlayHeightPx / 2f
    val anchor = dragController.reorderAnchorCenterListLocalY
    if (!anchor.isNaN() && abs(centerListLocalY - anchor) < reorderCenterMoveGatePx) return
    val itemCount = tracks.size
    if (itemCount == 0) return
    val candidateIndex = computeReorderDropIndex(
        listState = listState,
        draggedKey = key,
        draggedCenterY = centerListLocalY,
        itemsCount = itemCount
    )
    val currentIndex = tracks.indexOfFirst { it.id == key }
    if (currentIndex < 0) return
    if (candidateIndex == currentIndex) return
    val visible = layoutInfo.visibleItemsInfo.filter { it.index >= 0 && it.index < itemCount }
    val currentSlot = visible.find { it.index == currentIndex }
    if (currentSlot == null) return
    val slotTop = currentSlot.offset.toFloat()
    val slotBottom = currentSlot.offset + currentSlot.size
    val pastThreshold = when {
        candidateIndex > currentIndex -> centerListLocalY >= slotBottom + reorderThresholdPx
        candidateIndex < currentIndex -> centerListLocalY <= slotTop - reorderThresholdPx
        else -> true
    }
    if (!pastThreshold) return
    val ordered = reorderForDisplay(tracks, key, candidateIndex)
    val byId = tracks.associateBy { it.id }
    vm.setTrackOrderSession(projectId, ordered.map { byId.getValue(it.id) })
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

    val tracks = state.tracks

    val sessionGainByTrackId = remember { mutableStateMapOf<String, Float>() }
    LaunchedEffect(projectId) {
        sessionGainByTrackId.clear()
    }

    var listBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var listParentBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    val itemBoundsMap = remember { mutableStateMapOf<String, Rect>() }

    val density = LocalDensity.current
    val reorderThresholdPx = with(density) { Dimens.ReorderIndexThreshold.toPx() }
    val dragStartDeadzonePx = with(density) { Dimens.ReorderDragStartDeadzone.toPx() }
    val reorderCenterMoveGatePx = with(density) { Dimens.ReorderCenterMoveGate.toPx() }

    LaunchedEffect(
        dragController.fingerPos,
        dragController.draggingKey,
        listState,
        listBoundsInRoot,
        tracks,
        reorderThresholdPx,
        dragStartDeadzonePx,
        reorderCenterMoveGatePx,
        projectId
    ) {
        if (!dragController.isDragging) return@LaunchedEffect
        applyReorderTargetWithThreshold(
            vm = vm,
            projectId = projectId,
            tracks = tracks,
            dragController = dragController,
            listState = listState,
            listBoundsInRoot = listBoundsInRoot,
            reorderThresholdPx = reorderThresholdPx,
            dragStartDeadzonePx = dragStartDeadzonePx,
            reorderCenterMoveGatePx = reorderCenterMoveGatePx
        )
    }

    fun completeDrop() {
        if (dragController.draggingKey == null) return
        vm.persistTrackOrderToDb(projectId)
        dragController.end()
    }

    val reorderActive = dragController.isDragging

    ScreenScaffold(
        title = state.project?.name ?: "Project",
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
                        items = tracks,
                        key = { _, t -> t.id }
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
                                trackId = track.id,
                                interactionBlocked = reorderActive,
                                blockDragHandle = reorderActive && dragController.draggingKey != track.id,
                                dragHandleEnabled = rowFullyVisible,
                                onDragHandleStart = { positionInRoot ->
                                    if (!isTrackFullyVisibleInLazyList(listState, index)) return@TrackCard
                                    val bounds = itemBoundsMap[track.id] ?: return@TrackCard
                                    val offsetFromFinger = positionInRoot - Offset(bounds.left, bounds.top)
                                    val fixedXInParentPx = bounds.left - listParentBoundsInRoot.left
                                    val layoutInfo = listState.layoutInfo
                                    val fingerListLocalY = fingerYInListSpace(
                                        positionInRoot.y,
                                        listBoundsInRoot,
                                        layoutInfo.viewportStartOffset
                                    )
                                    dragController.start(
                                        key = track.id,
                                        startPos = positionInRoot,
                                        offsetFromFingerToItemTopLeft = offsetFromFinger,
                                        fixedXInParentPx = fixedXInParentPx,
                                        overlayWidthPx = bounds.right - bounds.left,
                                        overlayHeightPx = bounds.bottom - bounds.top,
                                        fingerListLocalY = fingerListLocalY
                                    )
                                },
                                onDragHandleMove = { positionInRoot ->
                                    dragController.update(positionInRoot)
                                },
                                onDragHandleEnd = { completeDrop() }
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
                        var overlayCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
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
                                                    completeDrop()
                                                    return@awaitEachGesture
                                                }
                                                else -> {}
                                            }
                                        } while (true)
                                    }
                                }
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
