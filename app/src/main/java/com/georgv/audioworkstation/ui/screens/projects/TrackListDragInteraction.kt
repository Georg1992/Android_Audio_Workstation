package com.georgv.audioworkstation.ui.screens.projects

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.layout.ProjectTrackLayoutSpec
import com.georgv.audioworkstation.ui.layout.pageEndExclusive
import com.georgv.audioworkstation.ui.layout.pageStartIndex

internal const val DropSettleDurationMs = 150

private const val PageEdgeHoldMs = 850L

/** After a neighbor swap, skip swap re-evaluation briefly to avoid oscillation from layout animation. */
private const val ReorderSwapCooldownMs = 48L

/** Cap neighbor-swap evaluation rate (overlay still follows every pointer update). */
private const val NeighborSwapEvalMinIntervalMs = 12L

internal sealed interface EdgeHoldBanner {
    data object None : EdgeHoldBanner

    data class Bottom(val progress: Float) : EdgeHoldBanner

    data class Top(val progress: Float) : EdgeHoldBanner
}

internal sealed interface DropCompletionPlan {
    data object FinishImmediate : DropCompletionPlan

    data class Settle(
        val snap: DropSettleSnap,
    ) : DropCompletionPlan
}

internal data class DropSettleSnap(
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

internal class TrackListDragInteractionState(
    val edgeHoldBanner: EdgeHoldBanner,
    val completeDrop: () -> Unit,
    val onDragHandleStarted: (String) -> Unit,
    val dragSurfaceModifier: Modifier,
)

@Composable
internal fun rememberTrackListDragInteraction(
    dragController: DragController,
    tracks: List<TrackEntity>,
    selectedTrackIds: Set<String>,
    recordingTrackId: String?,
    onReorderTracks: (List<TrackEntity>) -> Unit,
    onPersistTrackOrder: () -> Unit,
    pagerState: PagerState,
    pageSize: Int,
    listBoundsInRoot: Rect,
    listParentBoundsInRoot: Rect,
    itemBoundsMap: Map<String, Rect>,
    trackLayout: ProjectTrackLayoutSpec,
    density: Density,
    onSetDropSettle: (DropSettleSnap) -> Unit,
    onAllocateSettleUid: () -> Long,
): TrackListDragInteractionState {
    var edgeHoldBanner by remember { mutableStateOf<EdgeHoldBanner>(EdgeHoldBanner.None) }

    var neighborSwapCooldownUntilMs by remember { mutableLongStateOf(0L) }
    var lastNeighborSwapEvalUptimeMs by remember { mutableLongStateOf(0L) }
    var draggingGlobalIndex by remember { mutableIntStateOf(-1) }

    val tracksSnap by rememberUpdatedState(tracks)
    val latestOnReorderTracks by rememberUpdatedState(onReorderTracks)
    val listBoundsSnap by rememberUpdatedState(listBoundsInRoot)
    val listParentBoundsSnap by rememberUpdatedState(listParentBoundsInRoot)
    val selectedTrackIdsSnap by rememberUpdatedState(selectedTrackIds)
    val recordingTrackIdSnap by rememberUpdatedState(recordingTrackId)

    val rowStridePx =
        remember(trackLayout, density) {
            with(density) {
                trackLayout.trackSlotHeight.toPx() + trackLayout.listVerticalSpacing.toPx()
            }
        }

    fun emitLocalReorderIfChanged(reordered: List<TrackEntity>): Boolean {
        if (!trackListOrderChanged(tracksSnap, reordered)) return false
        latestOnReorderTracks(reordered)
        return true
    }

    fun emitReorderAndRefreshDraggingIndex(reordered: List<TrackEntity>): Boolean {
        if (!emitLocalReorderIfChanged(reordered)) return false
        val k = dragController.draggingKey
        if (k != null) {
            draggingGlobalIndex = reordered.indexOfFirst { it.id == k }
        }
        return true
    }

    val completeDrop: () -> Unit = completeDrop@{
        val key = dragController.draggingKey ?: return@completeDrop
        when (
            val plan =
                planDropCompletion(
                    draggingKey = key,
                    tracks = tracksSnap,
                    dragController = dragController,
                    listParentBounds = listParentBoundsSnap,
                    listBounds = listBoundsSnap,
                    selectedTrackIds = selectedTrackIdsSnap,
                    recordingTrackId = recordingTrackIdSnap,
                    pagerCurrentPage = pagerState.currentPage,
                    pageSize = pageSize,
                    rowStridePx = rowStridePx,
                    allocateSettleUid = onAllocateSettleUid,
                )
        ) {
            DropCompletionPlan.FinishImmediate -> {
                onPersistTrackOrder()
                dragController.end()
            }
            is DropCompletionPlan.Settle -> {
                edgeHoldBanner = EdgeHoldBanner.None
                onSetDropSettle(plan.snap)
                onPersistTrackOrder()
                dragController.end()
            }
        }
    }

    val latestCompleteDrop by rememberUpdatedState(completeDrop)

    LaunchedEffect(dragController.draggingKey) {
        if (dragController.draggingKey == null) {
            neighborSwapCooldownUntilMs = 0L
            lastNeighborSwapEvalUptimeMs = 0L
            draggingGlobalIndex = -1
        }
    }

    val edgeBandPx =
        remember(density) {
            with(density) { 44.dp.toPx() }
        }

    // Movement updates come only from TrackReorderHandle (captures pointer during drag).
    // This block detects release -> completeDrop. Do not duplicate dragController.update here.
    // Do not key on listBoundsInRoot or currentPage: layout restarts would reset edge-hold state.
    LaunchedEffect(dragController.draggingKey, pageSize, edgeBandPx) {
        if (dragController.draggingKey == null) {
            edgeHoldBanner = EdgeHoldBanner.None
            return@LaunchedEffect
        }
        var edgeHoldMachine = EdgeHoldMachineState()
        snapshotFlow {
            EdgeHoldSnapshot(
                fingerYRoot = dragController.fingerY,
                draggingKey = dragController.draggingKey,
                listBounds = listBoundsSnap,
                currentPage = pagerState.currentPage,
            )
        }.collect { snap ->
            val draggingKeySnap = snap.draggingKey
            val globalIndex =
                if (draggingKeySnap != null) {
                    resolveDraggingGlobalIndex(tracksSnap, draggingKeySnap, draggingGlobalIndex).also {
                        if (it >= 0) draggingGlobalIndex = it
                    }
                } else {
                    -1
                }

            val edgeHoldResult =
                reduceEdgeHoldOnCollect(
                    machine = edgeHoldMachine,
                    fingerYRoot = snap.fingerYRoot,
                    draggingKey = draggingKeySnap,
                    tracks = tracksSnap,
                    globalIndex = globalIndex,
                    listBounds = snap.listBounds,
                    edgeBandPx = edgeBandPx,
                    currentPageIndex = snap.currentPage,
                    pageSize = pageSize,
                    nowUptimeMs = SystemClock.uptimeMillis(),
                    pageEdgeHoldMs = PageEdgeHoldMs,
                )
            edgeHoldBanner = edgeHoldResult.banner
            edgeHoldMachine = edgeHoldResult.machine
            edgeHoldResult.pageTransition?.let { transition ->
                emitReorderAndRefreshDraggingIndex(transition.reordered)
                pagerState.scrollToPage(transition.scrollToPage)
            }

            if (!dragController.isDragging) return@collect
            val nowNeighbor = SystemClock.uptimeMillis()
            if (nowNeighbor < neighborSwapCooldownUntilMs) return@collect
            if (nowNeighbor - lastNeighborSwapEvalUptimeMs < NeighborSwapEvalMinIntervalMs) {
                return@collect
            }
            lastNeighborSwapEvalUptimeMs = nowNeighbor
            val currentPageIdx = pagerState.currentPage.coerceAtLeast(0)
            val start = pageStartIndex(currentPageIdx, pageSize)
            val end = pageEndExclusive(tracksSnap.size, currentPageIdx, pageSize)
            val reordered =
                neighborSwapOnPageOrNull(
                    tracksSnap,
                    dragController,
                    start,
                    end,
                    itemBoundsMap,
                    knownGlobalIndex = draggingGlobalIndex,
                )
                    ?: return@collect
            if (emitReorderAndRefreshDraggingIndex(reordered)) {
                neighborSwapCooldownUntilMs = nowNeighbor + ReorderSwapCooldownMs
            }
        }
    }

    val dragSurfaceModifier =
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                do {
                    val event = awaitPointerEvent()
                    if (dragController.isDragging) {
                        if (event.changes.none { it.pressed }) {
                            latestCompleteDrop()
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }

    return TrackListDragInteractionState(
        edgeHoldBanner = edgeHoldBanner,
        completeDrop = latestCompleteDrop,
        onDragHandleStarted = { trackId ->
            draggingGlobalIndex = tracksSnap.indexOfFirst { it.id == trackId }
        },
        dragSurfaceModifier = dragSurfaceModifier,
    )
}
