package com.georgv.audioworkstation.ui.screens.projects

import android.os.SystemClock
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
    val onReorderDragStarted: (String) -> Unit,
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
    var dropCompleting by remember { mutableStateOf(false) }

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
        if (dropCompleting) return@completeDrop
        val key = dragController.draggingKey ?: return@completeDrop
        dropCompleting = true
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
            dropCompleting = false
        }
    }

    val edgeBandPx =
        remember(density) {
            with(density) { 44.dp.toPx() }
        }

    // Movement updates come only from the track card reorder gesture (captures pointer during drag).
    // This block detects release -> completeDrop. Do not duplicate dragController.update here.
    // Do not key on listBoundsInRoot or currentPage: layout restarts would reset edge-hold state.
    LaunchedEffect(dragController.draggingKey, pageSize, edgeBandPx) {
        if (dragController.draggingKey == null) {
            edgeHoldBanner = EdgeHoldBanner.None
            return@LaunchedEffect
        }
        var edgeHoldMachine = EdgeHoldMachineState()
        var lastObservedPage = pagerState.currentPage.coerceAtLeast(0)
        var lastPageEntrySwapKey: String? = null
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
            edgeHoldResult.pageScroll?.let { page ->
                pagerState.scrollToPage(page)
            }

            if (!dragController.isDragging || dropCompleting) return@collect

            val nowNeighbor = SystemClock.uptimeMillis()
            val currentPageIdx = pagerState.currentPage.coerceAtLeast(0)

            if (
                draggingKeySnap != null &&
                globalIndex >= 0 &&
                currentPageIdx != lastObservedPage
            ) {
                val fromPage = lastObservedPage
                val pageEntryKey = "$fromPage>$currentPageIdx:$draggingKeySnap"
                if (pageEntryKey != lastPageEntrySwapKey) {
                    crossPageEnterSwapOrNull(
                        tracks = tracksSnap,
                        draggedIndex = globalIndex,
                        fromPage = fromPage,
                        targetPage = currentPageIdx,
                        pageSize = pageSize,
                    )?.let { pageEntryReordered ->
                        if (emitReorderAndRefreshDraggingIndex(pageEntryReordered)) {
                            lastPageEntrySwapKey = pageEntryKey
                            neighborSwapCooldownUntilMs = nowNeighbor + ReorderSwapCooldownMs
                        }
                    }
                }
                lastObservedPage = currentPageIdx
            }

            if (nowNeighbor < neighborSwapCooldownUntilMs) return@collect
            if (nowNeighbor - lastNeighborSwapEvalUptimeMs < NeighborSwapEvalMinIntervalMs) {
                return@collect
            }
            lastNeighborSwapEvalUptimeMs = nowNeighbor
            val start = pageStartIndex(currentPageIdx, pageSize)
            val end = pageEndExclusive(tracksSnap.size, currentPageIdx, pageSize)
            val edgeZone =
                computeEdgeHoldCandidateZone(
                    fingerYRoot = snap.fingerYRoot,
                    listBounds = snap.listBounds,
                    edgeBandPx = edgeBandPx,
                    globalIndex = globalIndex,
                    listSize = tracksSnap.size,
                    currentPageIndex = currentPageIdx,
                    pageSize = pageSize,
                )
            val reordered =
                if (edgeZone == EdgeHoldZone.None) {
                    crossPageBoundarySwapOrNull(
                        tracksSnap,
                        dragController,
                        start,
                        end,
                        itemBoundsMap,
                        knownGlobalIndex = draggingGlobalIndex,
                    )
                        ?: neighborSwapOnPageOrNull(
                            tracksSnap,
                            dragController,
                            start,
                            end,
                            itemBoundsMap,
                            knownGlobalIndex = draggingGlobalIndex,
                        )
                } else {
                    null
                }
                    ?: return@collect
            if (emitReorderAndRefreshDraggingIndex(reordered)) {
                neighborSwapCooldownUntilMs = nowNeighbor + ReorderSwapCooldownMs
            }
        }
    }

    val parentBoundsState = rememberUpdatedState(listParentBoundsInRoot)

    // List-level finger tracking survives page-entry reorder: the ghost row's gesture is disposed
    // when the dragged track moves to another VerticalPager page.
    val dragSurfaceModifier =
        Modifier.pointerInput(dragController.draggingKey) {
            if (dragController.draggingKey == null) return@pointerInput
            awaitPointerEventScope {
                while (dragController.draggingKey != null) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.none { it.pressed }) {
                        latestCompleteDrop()
                        break
                    }
                    val bounds = parentBoundsState.value
                    event.changes.firstOrNull { it.pressed && it.positionChanged() }?.let { change ->
                        dragController.update(
                            Offset(
                                x = bounds.left + change.position.x,
                                y = bounds.top + change.position.y,
                            ),
                        )
                    }
                }
            }
        }

    return TrackListDragInteractionState(
        edgeHoldBanner = edgeHoldBanner,
        completeDrop = latestCompleteDrop,
        onReorderDragStarted = { trackId ->
            draggingGlobalIndex = tracksSnap.indexOfFirst { it.id == trackId }
        },
        dragSurfaceModifier = dragSurfaceModifier,
    )
}
