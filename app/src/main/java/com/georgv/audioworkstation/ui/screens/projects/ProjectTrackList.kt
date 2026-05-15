@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.georgv.audioworkstation.ui.screens.projects

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.movableContentOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.TrackCard
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.layout.pageCount
import com.georgv.audioworkstation.ui.layout.pageEndExclusive
import com.georgv.audioworkstation.ui.layout.pageIndexForTrackGlobalIndex
import com.georgv.audioworkstation.ui.layout.pageStartIndex
import com.georgv.audioworkstation.ui.layout.projectTrackLayoutSpec
import com.georgv.audioworkstation.ui.layout.rememberLayoutEnvironment
import com.georgv.audioworkstation.ui.layout.swapAdjacentAtBoundaryDown
import com.georgv.audioworkstation.ui.layout.swapAdjacentAtBoundaryUp
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlinx.coroutines.flow.first
private data class EdgeHoldSnapshot(
    val fingerYRoot: Float,
    val draggingKey: String?,
    val listBounds: Rect,
    val currentPage: Int,
)

private const val DropSettleDurationMs = 150

private const val PageEdgeHoldMs = 850

/** Ignore subpixel onGloballyPositioned noise so layout during placement does not rewrite bounds map. */
private const val ItemBoundsEpsilonPx = 1f

/** After a neighbor swap, skip swap re-evaluation briefly to avoid oscillation from layout animation. */
private const val ReorderSwapCooldownMs = 48L

/** Cap neighbor-swap evaluation rate (overlay still follows every pointer update). */
private const val NeighborSwapEvalMinIntervalMs = 12L

private val TrackRowPlacementSpec =
    tween<IntOffset>(durationMillis = 210, easing = FastOutSlowInEasing)

private val PageSliceIncomingSlideTween =
    tween<Float>(durationMillis = 210, easing = FastOutSlowInEasing)

private sealed interface EdgeHoldBanner {
    data object None : EdgeHoldBanner

    data class Bottom(val progress: Float) : EdgeHoldBanner

    data class Top(val progress: Float) : EdgeHoldBanner
}

private enum class EdgeHoldZone {
    None,
    Top,
    Bottom,
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

private data class StoredPageSlice(val page: Int, val orderedIds: List<String>)

private data class BottomFillSession(
    val sessionKey: String,
    val previousIds: List<String>,
    val currentIds: List<String>,
    val incomingBottomIds: Set<String>,
)

/** Contiguous tail of [current] absent from [previous]; same-length slices only (page refill). */
private fun contiguousBottomIncomingIds(previous: List<String>, current: List<String>): List<String> {
    if (previous.size != current.size || previous.isEmpty()) return emptyList()
    if (previous == current) return emptyList()
    val prevSet = previous.toSet()
    val tail = mutableListOf<String>()
    for (i in current.indices.reversed()) {
        val id = current[i]
        if (id !in prevSet) tail.add(0, id) else break
    }
    return tail
}

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
fun ProjectTrackList(
    tracks: List<TrackEntity>,
    selectedTrackIds: Set<String>,
    recordingTrackId: String?,
    dragController: DragController,
    onToggleSelect: (String) -> Unit,
    onDeleteTrack: (String) -> Unit,
    onGainChange: (String, Float) -> Unit,
    onGainCommit: (String, Float) -> Unit,
    onRenameTrack: (String, String) -> Unit,
    onToggleLoop: (String) -> Unit,
    onReorderTracks: (List<TrackEntity>) -> Unit,
    onPersistTrackOrder: () -> Unit,
    onTrackPagingSummaryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layoutEnvironment = rememberLayoutEnvironment()

    var lastRecordingPageJumpForId by remember { mutableStateOf<String?>(null) }

    var listBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var listParentBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    val itemBoundsMap = remember { mutableStateMapOf<String, Rect>() }
    var dropSettle by remember { mutableStateOf<DropSettleSnap?>(null) }
    var nextSettleUid by remember { mutableLongStateOf(1L) }

    var edgeHoldBanner by remember { mutableStateOf<EdgeHoldBanner>(EdgeHoldBanner.None) }

    var openOverflowMenuTrackId by remember { mutableStateOf<String?>(null) }

    val tracksSnap by rememberUpdatedState(tracks)
    val latestOnReorderTracks by rememberUpdatedState(onReorderTracks)

    var neighborSwapCooldownUntilMs by remember { mutableLongStateOf(0L) }
    var lastNeighborSwapEvalUptimeMs by remember { mutableLongStateOf(0L) }
    var draggingGlobalIndex by remember { mutableIntStateOf(-1) }

    fun emitLocalReorderIfChanged(reordered: List<TrackEntity>): Boolean {
        val cur = tracksSnap
        if (reordered.size != cur.size) {
            latestOnReorderTracks(reordered)
            return true
        }
        for (i in reordered.indices) {
            if (reordered[i].id != cur[i].id) {
                latestOnReorderTracks(reordered)
                return true
            }
        }
        return false
    }

    fun emitReorderAndRefreshDraggingIndex(reordered: List<TrackEntity>): Boolean {
        if (!emitLocalReorderIfChanged(reordered)) return false
        val k = dragController.draggingKey
        if (k != null) {
            draggingGlobalIndex = reordered.indexOfFirst { it.id == k }
        }
        return true
    }

    LaunchedEffect(dragController.draggingKey) {
        if (dragController.draggingKey == null) {
            neighborSwapCooldownUntilMs = 0L
            lastNeighborSwapEvalUptimeMs = 0L
            draggingGlobalIndex = -1
        }
    }

    LaunchedEffect(recordingTrackId) {
        if (recordingTrackId == null) lastRecordingPageJumpForId = null
    }

    val listInteractionLocked = dragController.isDragging || dropSettle != null
    val reorderActive = dragController.isDragging

    LaunchedEffect(listInteractionLocked) {
        if (listInteractionLocked) openOverflowMenuTrackId = null
    }

    Box(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val fullViewportH =
                remember(constraints.maxHeight, density) {
                    with(density) { constraints.maxHeight.toDp() }
                }
            val listViewportW =
                remember(constraints.maxWidth, density) {
                    with(density) { constraints.maxWidth.toDp() }
                }

            val trackLayout =
                remember(layoutEnvironment, fullViewportH, listViewportW) {
                    projectTrackLayoutSpec(layoutEnvironment, fullViewportH, listViewportW)
                }

            val pageSize =
                remember(trackLayout) { trackLayout.targetVisibleTrackSlots.coerceAtLeast(1) }

            val totalPages =
                remember(tracks.size, pageSize) {
                    pageCount(tracks.size, pageSize)
                }

            val pagerState =
                rememberPagerState(
                    pageCount = { pageCount(tracks.size, pageSize).coerceAtLeast(1) },
                )

            val completeDrop: () -> Unit = dropAction@{
                val key = dragController.draggingKey ?: return@dropAction
                val trackEntity = tracksSnap.find { it.id == key }
                val parentTop = listParentBoundsInRoot.top
                val parentH =
                    listParentBoundsInRoot.bottom - listParentBoundsInRoot.top

                fun finishImmediate() {
                    onPersistTrackOrder()
                    dragController.end()
                }

                if (trackEntity == null) {
                    finishImmediate()
                    return@dropAction
                }

                val wPx = dragController.overlayWidthPx
                val hPx = dragController.overlayHeightPx

                val startY =
                    (dragController.fingerY -
                        dragController.dragOffset.y -
                        parentTop).coerceIn(
                        0f,
                        (parentH - hPx).coerceAtLeast(0f),
                    )

                if (!startY.isFinite() || wPx <= 0f || hPx <= 0f || !parentTop.isFinite()) {
                    finishImmediate()
                    return@dropAction
                }

                val lb = listBoundsInRoot
                if (lb.isEmpty) {
                    finishImmediate()
                    return@dropAction
                }

                val slotPx = with(density) { trackLayout.trackSlotHeight.toPx() }
                val spacingPx = with(density) { trackLayout.listVerticalSpacing.toPx() }
                val rowStride = slotPx + spacingPx

                val globalIdx = tracksSnap.indexOfFirst { it.id == key }
                if (globalIdx < 0) {
                    finishImmediate()
                    return@dropAction
                }

                val pageIdx = pagerState.currentPage.coerceAtLeast(0)
                val sliceStart = pageStartIndex(pageIdx, pageSize)
                val sliceEnd = pageEndExclusive(tracksSnap.size, pageIdx, pageSize)

                val targetY =
                    if (globalIdx in sliceStart until sliceEnd) {
                        val indexInPage = globalIdx - sliceStart
                        val targetTopRoot = lb.top + indexInPage * rowStride
                        targetTopRoot - parentTop
                    } else {
                        startY
                    }

                if (!targetY.isFinite()) {
                    finishImmediate()
                    return@dropAction
                }

                edgeHoldBanner = EdgeHoldBanner.None
                dropSettle =
                    DropSettleSnap(
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

            val latestCompleteDrop by rememberUpdatedState(completeDrop)

            LaunchedEffect(tracks.size, pageSize, totalPages, pagerState) {
                val lastIdx = totalPages.coerceAtLeast(1) - 1
                if (pagerState.currentPage > lastIdx) pagerState.scrollToPage(lastIdx.coerceAtLeast(0))
            }

            LaunchedEffect(pagerState.settledPage, totalPages, tracks.isEmpty()) {
                val denom = totalPages.coerceAtLeast(1)
                val pageIdx = pagerState.settledPage.coerceIn(0, denom - 1)
                onTrackPagingSummaryChange("${pageIdx + 1}/$denom")
            }

            LaunchedEffect(recordingTrackId, tracks, pageSize, totalPages, pagerState) {
                val id = recordingTrackId ?: return@LaunchedEffect
                if (lastRecordingPageJumpForId == id) return@LaunchedEffect
                val idx =
                    snapshotFlow { tracks.indexOfFirst { it.id == id } }
                        .first { it >= 0 }
                val pc = pageCount(tracks.size, pageSize)
                val target =
                    pageIndexForTrackGlobalIndex(idx, pageSize).coerceIn(0, (pc - 1).coerceAtLeast(0))
                pagerState.scrollToPage(target)
                lastRecordingPageJumpForId = id
            }

            val currentPageIdx = pagerState.currentPage.coerceAtLeast(0)
            val currentSliceIds =
                remember(tracks, currentPageIdx, pageSize) {
                    val s = pageStartIndex(currentPageIdx, pageSize)
                    val e =
                        pageEndExclusive(tracks.size, currentPageIdx, pageSize).coerceAtLeast(s)
                    if (s >= tracks.size || e <= s) {
                        emptyList()
                    } else {
                        tracks.subList(s, minOf(e, tracks.size)).map { it.id }
                    }
                }

            val sliceCommitTracks by rememberUpdatedState(tracks)
            val sliceCommitPage by rememberUpdatedState(pagerState.currentPage)
            val sliceCommitPageSize by rememberUpdatedState(pageSize)

            var storedPageSlice by remember { mutableStateOf<StoredPageSlice?>(null) }

            val activeBottomFill: BottomFillSession? =
                remember(
                    storedPageSlice,
                    currentSliceIds,
                    currentPageIdx,
                    listInteractionLocked,
                    reorderActive,
                ) {
                    if (listInteractionLocked || reorderActive) null
                    else {
                        val snap = storedPageSlice
                        when {
                            snap == null -> null
                            snap.page != currentPageIdx -> null
                            else -> {
                                val incomingList =
                                    contiguousBottomIncomingIds(snap.orderedIds, currentSliceIds)
                                if (incomingList.isEmpty()) null
                                else
                                    BottomFillSession(
                                        sessionKey =
                                            snap.orderedIds.joinToString(",") + ">" +
                                                currentSliceIds.joinToString(","),
                                        previousIds = snap.orderedIds,
                                        currentIds = currentSliceIds,
                                        incomingBottomIds = incomingList.toSet(),
                                    )
                            }
                        }
                    }
                }

            SideEffect {
                if (!listInteractionLocked && !reorderActive && activeBottomFill == null) {
                    storedPageSlice = StoredPageSlice(currentPageIdx, currentSliceIds)
                }
            }

            LaunchedEffect(activeBottomFill?.sessionKey) {
                activeBottomFill ?: return@LaunchedEffect
                try {
                    val gate = Animatable(0f)
                    gate.animateTo(1f, PageSliceIncomingSlideTween)
                } finally {
                    val p = sliceCommitPage.coerceAtLeast(0)
                    val n = sliceCommitTracks.size
                    val ps = sliceCommitPageSize.coerceAtLeast(1)
                    val s = pageStartIndex(p, ps)
                    val e = pageEndExclusive(n, p, ps).coerceAtLeast(s)
                    val ids =
                        if (s >= n || e <= s) {
                            emptyList()
                        } else {
                            sliceCommitTracks.subList(s, minOf(e, n)).map { it.id }
                        }
                    storedPageSlice = StoredPageSlice(p, ids)
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
                var armedZone = EdgeHoldZone.None
                var zoneEnterUptimeMs = 0L
                snapshotFlow {
                    EdgeHoldSnapshot(
                        fingerYRoot = dragController.fingerY,
                        draggingKey = dragController.draggingKey,
                        listBounds = listBoundsInRoot,
                        currentPage = pagerState.currentPage,
                    )
                }.collect { snap ->
                    val fingerYRoot = snap.fingerYRoot
                    val draggingKeySnap = snap.draggingKey
                    val lb = snap.listBounds
                    val currentPageIdxSnap = snap.currentPage

                    if (draggingKeySnap != null) {
                        val list = tracksSnap
                        val key = draggingKeySnap
                        val gi =
                            draggingGlobalIndex.let { cached ->
                                if (cached >= 0 && cached < list.size && list[cached].id == key) {
                                    cached
                                } else {
                                    val found = list.indexOfFirst { it.id == key }
                                    if (found >= 0) draggingGlobalIndex = found
                                    found
                                }
                            }

                        if (gi < 0 || lb.isEmpty) {
                            edgeHoldBanner = EdgeHoldBanner.None
                            armedZone = EdgeHoldZone.None
                        } else {
                            val currentPageIdx = currentPageIdxSnap.coerceAtLeast(0)
                            val start = pageStartIndex(currentPageIdx, pageSize)
                            val end = pageEndExclusive(list.size, currentPageIdx, pageSize)
                            val inBottomBand = fingerYRoot >= lb.bottom - edgeBandPx
                            val inTopBand = fingerYRoot <= lb.top + edgeBandPx
                            val canMoveDown = gi == end - 1 && end < list.size
                            val canMoveUp = gi == start && start > 0
                            val now = SystemClock.uptimeMillis()
                            val candidate =
                                when {
                                    canMoveDown && inBottomBand -> EdgeHoldZone.Bottom
                                    canMoveUp && inTopBand -> EdgeHoldZone.Top
                                    else -> EdgeHoldZone.None
                                }
                            if (candidate == EdgeHoldZone.None) {
                                armedZone = EdgeHoldZone.None
                                edgeHoldBanner = EdgeHoldBanner.None
                            } else {
                                if (candidate != armedZone) {
                                    armedZone = candidate
                                    zoneEnterUptimeMs = now
                                }
                                val heldMs = now - zoneEnterUptimeMs
                                if (heldMs < PageEdgeHoldMs) {
                                    val p = heldMs / PageEdgeHoldMs.toFloat()
                                    edgeHoldBanner =
                                        when (armedZone) {
                                            EdgeHoldZone.Bottom -> EdgeHoldBanner.Bottom(p)
                                            EdgeHoldZone.Top -> EdgeHoldBanner.Top(p)
                                            EdgeHoldZone.None -> EdgeHoldBanner.None
                                        }
                                } else {
                                    val reordered =
                                        when (armedZone) {
                                            EdgeHoldZone.Bottom -> swapAdjacentAtBoundaryDown(list, gi)
                                            EdgeHoldZone.Top -> swapAdjacentAtBoundaryUp(list, gi)
                                            EdgeHoldZone.None -> null
                                        }
                                    val transitionDown = armedZone == EdgeHoldZone.Bottom
                                    val transitionUp = armedZone == EdgeHoldZone.Top
                                    armedZone = EdgeHoldZone.None
                                    edgeHoldBanner = EdgeHoldBanner.None
                                    zoneEnterUptimeMs = SystemClock.uptimeMillis()
                                    if (reordered != null) {
                                        emitReorderAndRefreshDraggingIndex(reordered)
                                        val lastPage = pageCount(reordered.size, pageSize).coerceAtLeast(1) - 1
                                        when {
                                            transitionDown ->
                                                pagerState.scrollToPage(
                                                    (currentPageIdx + 1).coerceAtMost(lastPage),
                                                )
                                            transitionUp ->
                                                pagerState.scrollToPage(
                                                    (currentPageIdx - 1).coerceAtLeast(0),
                                                )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        edgeHoldBanner = EdgeHoldBanner.None
                        armedZone = EdgeHoldZone.None
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
                    val end =
                        pageEndExclusive(tracksSnap.size, currentPageIdx, pageSize)
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

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coords ->
                            listParentBoundsInRoot = coords.boundsInRoot()
                        }
                        .pointerInput(Unit) {
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
            ) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    VerticalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !listInteractionLocked,
                        beyondViewportPageCount = 1,
                        pageSpacing = trackLayout.listVerticalSpacing,
                    ) { page ->
                    val start = pageStartIndex(page, pageSize)
                    val end = pageEndExclusive(tracks.size, page, pageSize)
                    val pageTracks =
                        remember(tracks, start, end) {
                            val e = end.coerceAtLeast(start)
                            if (start >= tracks.size || e <= start) {
                                emptyList()
                            } else {
                                tracks.subList(start, e).toList()
                            }
                        }

                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = Dimens.TileInnerPadding)
                                .then(
                                    if (page == pagerState.currentPage) {
                                        Modifier.onGloballyPositioned { coords ->
                                            listBoundsInRoot = coords.boundsInRoot()
                                        }
                                    } else {
                                        Modifier
                                    }
                                ),
                        verticalArrangement =
                            Arrangement.spacedBy(trackLayout.listVerticalSpacing),
                        userScrollEnabled = false,
                    ) {
                        itemsIndexed(pageTracks, key = { _, t -> t.id }) { _, track ->
                            @Composable
                            fun RowTrackCard() {
                                TrackCard(
                                    title = track.name ?: "Track",
                                    isSelected = selectedTrackIds.contains(track.id),
                                    isRecording = recordingTrackId == track.id,
                                    gain = track.gain,
                                    onGainChange = { gain ->
                                        onGainChange(track.id, gain)
                                    },
                                    onGainCommit = { gain ->
                                        onGainCommit(track.id, gain)
                                    },
                                    onClick = { onToggleSelect(track.id) },
                                    onDelete = { onDeleteTrack(track.id) },
                                    onRename = { onRenameTrack(track.id, it) },
                                    onToggleLoop = { onToggleLoop(track.id) },
                                    isLoop = track.isLoop,
                                    trackId = track.id,
                                    trackSlotHeight = trackLayout.trackSlotHeight,
                                    interactionBlocked = listInteractionLocked,
                                    blockDragHandle =
                                        (reorderActive &&
                                            dragController.draggingKey !=
                                                track.id) ||
                                            dropSettle != null,
                                    dragHandleEnabled = true,
                                    onDragHandleStart = { positionInRoot ->
                                        val bounds =
                                            itemBoundsMap[track.id]
                                                ?: return@TrackCard
                                        val offsetFromFinger =
                                            positionInRoot - Offset(bounds.left, bounds.top)
                                        val fixedXInParentPx =
                                            bounds.left - listParentBoundsInRoot.left
                                        dragController.start(
                                            key = track.id,
                                            startPos = positionInRoot,
                                            offsetFromFingerToItemTopLeft = offsetFromFinger,
                                            fixedXInParentPx = fixedXInParentPx,
                                            overlayWidthPx = bounds.right - bounds.left,
                                            overlayHeightPx = bounds.bottom - bounds.top
                                        )
                                        draggingGlobalIndex =
                                            tracksSnap.indexOfFirst { it.id == track.id }
                                    },
                                    onDragHandleMove = { positionInRoot ->
                                        dragController.update(positionInRoot)
                                    },
                                    onDragHandleEnd = { latestCompleteDrop() },
                                    isMenuOpen = openOverflowMenuTrackId == track.id,
                                    onMenuOpen = {
                                        openOverflowMenuTrackId = track.id
                                    },
                                    onMenuDismiss = {
                                        if (openOverflowMenuTrackId == track.id) {
                                            openOverflowMenuTrackId = null
                                        }
                                    },
                                )
                            }

                            val isGhostRow =
                                (reorderActive && dragController.draggingKey == track.id) ||
                                    (dropSettle?.trackId == track.id)

                            val currentPagerPage = pagerState.currentPage
                            val incomingSession =
                                activeBottomFill?.takeIf {
                                    page == currentPagerPage &&
                                        it.incomingBottomIds.contains(track.id)
                                }

                            Box(
                                modifier =
                                    Modifier
                                        .animateItem(
                                            fadeInSpec = null,
                                            fadeOutSpec = null,
                                            placementSpec =
                                                when {
                                                    incomingSession != null -> null
                                                    isGhostRow -> null
                                                    else -> TrackRowPlacementSpec
                                                },
                                        )
                                        .onGloballyPositioned { coords ->
                                            val r = coords.boundsInRoot()
                                            val id = track.id
                                            val prev = itemBoundsMap[id]
                                            if (prev == null ||
                                                !prev.nearlyEqualsTo(r, ItemBoundsEpsilonPx)
                                            ) {
                                                itemBoundsMap[id] = r
                                            }
                                        }
                                        .alpha(if (isGhostRow) 0f else 1f),
                            ) {
                                if (incomingSession != null) {
                                    PageSliceBottomIncomingSlide(
                                        sessionKey = incomingSession.sessionKey,
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
                    }
                }
                }

                when (val banner = edgeHoldBanner) {
                    EdgeHoldBanner.None -> Unit
                    is EdgeHoldBanner.Bottom -> {
                        val shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .zIndex(2f)
                                .padding(horizontal = Dimens.TileInnerPadding)
                                .padding(bottom = Dimens.TileInnerPadding)
                                .fillMaxWidth(0.5f)
                                .height(4.dp)
                                .alpha(
                                    (0.45f + 0.52f * banner.progress).coerceIn(
                                        0f,
                                        1f,
                                    )
                                )
                                .border(
                                    width = 1.dp,
                                    color = AppColors.Line.copy(alpha = 0.9f),
                                    shape = shape,
                                )
                                .background(AppColors.Line.copy(alpha = 0.08f), shape),
                        )
                    }

                    is EdgeHoldBanner.Top -> {
                        val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .zIndex(2f)
                                .padding(horizontal = Dimens.TileInnerPadding)
                                .padding(top = Dimens.TileInnerPadding)
                                .fillMaxWidth(0.5f)
                                .height(4.dp)
                                .alpha(
                                    (0.45f + 0.52f * banner.progress).coerceIn(
                                        0f,
                                        1f,
                                    )
                                )
                                .border(
                                    width = 1.dp,
                                    color = AppColors.Line.copy(alpha = 0.9f),
                                    shape = shape,
                                )
                                .background(AppColors.Line.copy(alpha = 0.08f), shape),
                        )
                    }
                }

                val settleSnap = dropSettle
                when {
                    settleSnap != null -> {
                        val settleYAnim =
                            remember(settleSnap.settleUid) {
                                Animatable(settleSnap.startTranslationYPx)
                            }
                        LaunchedEffect(settleSnap.settleUid) {
                            try {
                                settleYAnim.snapTo(settleSnap.startTranslationYPx)
                                settleYAnim.animateTo(
                                    settleSnap.targetTranslationYPx,
                                    animationSpec =
                                        tween(
                                            durationMillis = DropSettleDurationMs,
                                            easing = FastOutSlowInEasing,
                                        ),
                                )
                            } finally {
                                if (dropSettle?.settleUid == settleSnap.settleUid) {
                                    dropSettle = null
                                }
                            }
                        }
                        TrackDragSettlingOverlay(
                            modifier = Modifier.zIndex(1f),
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

                    dragController.isDragging -> {
                        val draggedTrack =
                            dragController.draggingKey?.let { id ->
                                tracksSnap.find { it.id == id }
                            }
                        if (draggedTrack != null) {
                            TrackDragOverlay(
                                modifier = Modifier.zIndex(1f),
                                track = draggedTrack,
                                isSelected =
                                    selectedTrackIds.contains(draggedTrack.id),
                                isRecording = recordingTrackId == draggedTrack.id,
                                gain = draggedTrack.gain,
                                dragController = dragController,
                                parentTopInRootPx = listParentBoundsInRoot.top,
                                parentHeightPx =
                                    listParentBoundsInRoot.bottom -
                                        listParentBoundsInRoot.top,
                            )
                        }
                    }
                }
            }
        }
    }
}
