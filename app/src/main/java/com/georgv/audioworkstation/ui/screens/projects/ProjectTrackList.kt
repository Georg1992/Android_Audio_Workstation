@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.TimelineClip
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.layout.pageCount
import com.georgv.audioworkstation.ui.layout.pageEndExclusive
import com.georgv.audioworkstation.ui.layout.pageIndexForTrackGlobalIndex
import com.georgv.audioworkstation.ui.layout.pageStartIndex
import com.georgv.audioworkstation.ui.layout.projectTrackLayoutSpec
import com.georgv.audioworkstation.ui.layout.rememberLayoutEnvironment
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlinx.coroutines.flow.first

private val PageSliceIncomingSlideTween =
    tween<Float>(durationMillis = 210, easing = FastOutSlowInEasing)

private const val EdgeHoldBannerAlphaMin = 0.45f
private const val EdgeHoldBannerAlphaSpan = 0.52f

internal fun trackActionsEnabled(playbackActive: Boolean): Boolean = !playbackActive

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

@Composable
fun ProjectTrackList(
    tracks: List<TrackEntity>,
    selectedTrackIds: Set<String>,
    recordingTrackId: String?,
    recordTargetTrackId: String?,
    recordingInputLevel: Float,
    timelineClipsByTrackId: Map<String, TimelineClip>,
    timelineLaneLayoutDurationMs: Long,
    timelineVisibleDurationMs: Long,
    timelinePlayheadPositionMs: Long,
    playbackActive: Boolean,
    dragController: DragController,
    onToggleSelect: (String) -> Unit,
    onDeleteTrack: (String) -> Unit,
    onGainChange: (String, Float) -> Unit,
    onGainCommit: (String, Float) -> Unit,
    onRenameTrack: (String, String) -> Unit,
    onToggleRecordTarget: (String) -> Unit,
    onToggleLoop: (String) -> Unit,
    onUpdateTrackLoopRegion: (String, Long, Long) -> Unit,
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

    var openOverflowMenuTrackId by remember { mutableStateOf<String?>(null) }

    val tracksSnap by rememberUpdatedState(tracks)

    LaunchedEffect(recordingTrackId) {
        if (recordingTrackId == null) lastRecordingPageJumpForId = null
    }

    val listInteractionLocked = dragController.isDragging || dropSettle != null
    val reorderActive = dragController.isDragging
    val trackActionsEnabled = trackActionsEnabled(playbackActive)
    LaunchedEffect(listInteractionLocked, trackActionsEnabled) {
        if (listInteractionLocked || !trackActionsEnabled) openOverflowMenuTrackId = null
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

            val dragInteraction =
                rememberTrackListDragInteraction(
                    dragController = dragController,
                    tracks = tracks,
                    selectedTrackIds = selectedTrackIds,
                    recordingTrackId = recordingTrackId,
                    onReorderTracks = onReorderTracks,
                    onPersistTrackOrder = onPersistTrackOrder,
                    pagerState = pagerState,
                    pageSize = pageSize,
                    listBoundsInRoot = listBoundsInRoot,
                    listParentBoundsInRoot = listParentBoundsInRoot,
                    itemBoundsMap = itemBoundsMap,
                    trackLayout = trackLayout,
                    density = density,
                    onSetDropSettle = { dropSettle = it },
                    onAllocateSettleUid = { nextSettleUid++ },
                )

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

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coords ->
                            listParentBoundsInRoot = coords.boundsInRoot()
                        }
                        .then(dragInteraction.dragSurfaceModifier),
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
                            val currentPagerPage = pagerState.currentPage
                            val incomingSession =
                                activeBottomFill?.takeIf {
                                    page == currentPagerPage &&
                                        it.incomingBottomIds.contains(track.id)
                                }
                            ProjectTrackListRow(
                                track = track,
                                selectedTrackIds = selectedTrackIds,
                                recordingTrackId = recordingTrackId,
                                recordTargetTrackId = recordTargetTrackId,
                                recordingInputLevel = recordingInputLevel,
                                timelineClipsByTrackId = timelineClipsByTrackId,
                                timelineLaneLayoutDurationMs = timelineLaneLayoutDurationMs,
                                timelineVisibleDurationMs = timelineVisibleDurationMs,
                                timelinePlayheadPositionMs = timelinePlayheadPositionMs,
                                trackLayout = trackLayout,
                                playbackActive = playbackActive,
                                trackActionsEnabled = trackActionsEnabled,
                                listInteractionLocked = listInteractionLocked,
                                reorderActive = reorderActive,
                                dragController = dragController,
                                dropSettleInProgress = dropSettle != null,
                                dropSettlingTrackId = dropSettle?.trackId,
                                itemBoundsMap = itemBoundsMap,
                                listParentBoundsInRoot = listParentBoundsInRoot,
                                incomingSlideSessionKey = incomingSession?.sessionKey,
                                isMenuOpen = openOverflowMenuTrackId == track.id,
                                onMenuOpen = {
                                    if (trackActionsEnabled) {
                                        openOverflowMenuTrackId = track.id
                                    }
                                },
                                onMenuDismiss = {
                                    if (openOverflowMenuTrackId == track.id) {
                                        openOverflowMenuTrackId = null
                                    }
                                },
                                onToggleSelect = onToggleSelect,
                                onDeleteTrack = onDeleteTrack,
                                onGainChange = onGainChange,
                                onGainCommit = onGainCommit,
                                onRenameTrack = onRenameTrack,
                                onToggleRecordTarget = onToggleRecordTarget,
                                onToggleLoop = onToggleLoop,
                                onUpdateTrackLoopRegion = onUpdateTrackLoopRegion,
                                onDragHandleEnd = dragInteraction.completeDrop,
                                onDragHandleStarted = dragInteraction.onDragHandleStarted,
                            )
                        }
                    }
                }
                }

                when (val banner = dragInteraction.edgeHoldBanner) {
                    EdgeHoldBanner.None -> Unit
                    is EdgeHoldBanner.Bottom -> {
                        val shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .zIndex(2f)
                                .padding(horizontal = Dimens.TrackCardOuterPaddingHorizontal)
                                .padding(bottom = Dimens.TileInnerPadding)
                                .fillMaxWidth(0.5f)
                                .height(4.dp)
                                .alpha(
                                    (EdgeHoldBannerAlphaMin + EdgeHoldBannerAlphaSpan * banner.progress).coerceIn(
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
                                .padding(horizontal = Dimens.TrackCardOuterPaddingHorizontal)
                                .padding(top = Dimens.TileInnerPadding)
                                .fillMaxWidth(0.5f)
                                .height(4.dp)
                                .alpha(
                                    (EdgeHoldBannerAlphaMin + EdgeHoldBannerAlphaSpan * banner.progress).coerceIn(
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
