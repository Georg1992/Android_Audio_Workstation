package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.TrackCard
import com.georgv.audioworkstation.ui.components.TransportPanel
import com.georgv.audioworkstation.ui.theme.AppColors
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TRACK_START_INDEX_IN_LAZY = 1

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
            val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            "QuickRec_$time"
        } else "New Project"
        vm.ensureProjectExists(projectId, projectName)
        if (quickRecord) vm.addTrack(projectId)
    }

    val state by vm.uiState.collectAsState()
    val listState = rememberLazyListState()

    var draggingId by remember { mutableStateOf<String?>(null) }
    var activePointerId by remember { mutableStateOf<PointerId?>(null) }
    var lastDownId by remember { mutableStateOf<PointerId?>(null) }
    var fingerPosInRoot by remember { mutableStateOf(Offset.Zero) }
    var lazyTopInRoot by remember { mutableStateOf(0f) }
    var boxTopLeftInRoot by remember { mutableStateOf(Offset.Zero) }
    var dropIndex by remember { mutableStateOf(0) }

    val draggedId = draggingId
    val listTracks = remember(state.tracks, draggedId) {
        if (draggedId == null) state.tracks else state.tracks.filterNot { it.id == draggedId }
    }

    fun computeDropIndexFromFinger(fingerYInRoot: Float): Int {
        if (draggingId == null) return 0
        if (listTracks.isEmpty()) return 0
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return 0.coerceIn(0, listTracks.size)
        val fingerYInLazy = fingerYInRoot - lazyTopInRoot
        val trackInfos = visible.filter { it.index >= TRACK_START_INDEX_IN_LAZY }
        if (trackInfos.isEmpty()) return 0.coerceIn(0, listTracks.size)
        val nearest = trackInfos.minByOrNull { info ->
            val center = info.offset + info.size / 2f
            abs(center - fingerYInLazy)
        } ?: return 0.coerceIn(0, listTracks.size)
        val nearestCenter = nearest.offset + nearest.size / 2f
        val insertAfter = fingerYInLazy > nearestCenter
        val listTrackIndex = (nearest.index - TRACK_START_INDEX_IN_LAZY).coerceIn(0, listTracks.size - 1)
        val idx = if (insertAfter) listTrackIndex + 1 else listTrackIndex
        return idx.coerceIn(0, listTracks.size)
    }

    ScreenScaffold(title = state.project?.name ?: "Project", onBack = onBack) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { boxTopLeftInRoot = it.positionInRoot() }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Final)
                        lastDownId = down.id
                        fingerPosInRoot = down.position + boxTopLeftInRoot

                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val ptrId = activePointerId
                            if (ptrId != null) {
                                val change = event.changes.firstOrNull { it.id == ptrId }
                                if (change != null) {
                                    fingerPosInRoot = change.position + boxTopLeftInRoot
                                    dropIndex = computeDropIndexFromFinger(fingerPosInRoot.y)
                                    change.consumePositionChange()

                                    if (!change.pressed) {
                                        val dropTrackId = draggingId
                                        if (dropTrackId != null) {
                                            vm.moveTrack(projectId, dropTrackId, dropIndex)
                                        }
                                        draggingId = null
                                        activePointerId = null
                                        dropIndex = 0
                                        break
                                    }
                                } else {
                                    draggingId = null
                                    activePointerId = null
                                    dropIndex = 0
                                    break
                                }
                            }
                        }
                    }
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .onGloballyPositioned { lazyTopInRoot = it.positionInRoot().y },
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "header") {
                    Text("Tracks: ${state.tracks.size}", color = AppColors.Line)
                }
                items(items = listTracks, key = { it.id }) { track ->
                    TrackCard(
                        title = track.name ?: "Track",
                        isSelected = state.selectedTrackIds.contains(track.id),
                        isRecording = state.recordingTrackId == track.id,
                        onClick = { vm.toggleSelect(track.id) },
                        onDragHandleLongPress = { positionInRoot ->
                            draggingId = track.id
                            activePointerId = lastDownId
                            fingerPosInRoot = positionInRoot
                            dropIndex = computeDropIndexFromFinger(positionInRoot.y)
                        }
                    )
                }
            }

            if (draggedId != null && activePointerId != null) {
                val noteSize = 56.dp
                val noteHalfPx = with(androidx.compose.ui.platform.LocalDensity.current) { noteSize.toPx() / 2f }
                val noteOffsetInBox = fingerPosInRoot - boxTopLeftInRoot

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (noteOffsetInBox.x - noteHalfPx).roundToInt(),
                                (noteOffsetInBox.y - noteHalfPx).roundToInt()
                            )
                        }
                        .size(noteSize)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.Yellow)
                        .border(2.dp, AppColors.Line, RoundedCornerShape(10.dp))
                        .graphicsLayer { scaleX = 0.92f; scaleY = 0.92f },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = "Dragging",
                        tint = AppColors.Line
                    )
                }
            }

            TransportPanel(
                isRecording = state.recordingTrackId != null,
                isPlaying = false,
                isPlayEnabled = state.selectedTrackIds.isNotEmpty(),
                isStopEnabled = state.recordingTrackId != null,
                onPlay = { vm.onPlayPressed() },
                onStop = { vm.onStopPressed() },
                onRecord = { vm.onRecordPressed(projectId) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
