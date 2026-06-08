@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.core.track.hasPersistedPlayableAudio
import com.georgv.audioworkstation.core.track.trackLaneGlobalOverlayTimelineDurationMs
import com.georgv.audioworkstation.ui.components.TimelineClip
import com.georgv.audioworkstation.ui.components.timelineLaneLocalLayoutDurationMs
import com.georgv.audioworkstation.ui.components.TrackCard
import com.georgv.audioworkstation.ui.components.loopRegionEditingEnabled
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.layout.ProjectTrackLayoutSpec

/** Ignore subpixel onGloballyPositioned noise so layout during placement does not rewrite bounds map. */
private const val ItemBoundsEpsilonPx = 1f

private val TrackRowPlacementSpec =
    tween<IntOffset>(durationMillis = 210, easing = FastOutSlowInEasing)

private val PageSliceIncomingSlideTween =
    tween<Float>(durationMillis = 210, easing = FastOutSlowInEasing)

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
internal fun LazyItemScope.ProjectTrackListRow(
    track: TrackEntity,
    selectedTrackIds: Set<String>,
    recordingTrackId: String?,
    recordTargetTrackId: String?,
    @Suppress("UNUSED_PARAMETER") sessionTrackIds: Set<String>,
    importInProgress: Boolean,
    realtimeUiState: StateFlow<ProjectRealtimeUiState>,
    timelineClipsByTrackId: Map<String, TimelineClip>,
    timelineLaneLayoutDurationMs: Long,
    trackLayout: ProjectTrackLayoutSpec,
    playbackActive: Boolean,
    showWaveforms: Boolean = true,
    trackActionsEnabled: Boolean,
    listInteractionLocked: Boolean,
    reorderActive: Boolean,
    dragController: DragController,
    dropSettleInProgress: Boolean,
    dropSettlingTrackId: String?,
    suppressRowPlacementAfterSettle: Boolean,
    itemBoundsMap: MutableMap<String, Rect>,
    listParentBoundsInRoot: Rect,
    incomingSlideSessionKey: String?,
    isMenuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onDeleteTrack: (String) -> Unit,
    onCancelImport: (String) -> Unit,
    onGainChange: (String, Float) -> Unit,
    onGainCommit: (String, Float) -> Unit,
    gainFaderEnabled: Boolean,
    onGainDragStart: (String) -> Unit,
    onGainDragEnd: (String) -> Unit,
    onPanChange: (String, Float) -> Unit,
    onPanCommit: (String, Float) -> Unit,
    onRenameTrack: (String, String) -> Unit,
    onToggleRecordTarget: (String) -> Unit,
    onToggleLoop: (String) -> Unit,
    onUpdateTrackLoopRegion: (String, Long, Long) -> Unit,
    onEditTrack: (String) -> Unit,
    ignoredOnReorderDragEnd: () -> Unit,
    onReorderDragStarted: (trackId: String) -> Unit,
) {
    val needsLiveMeter = recordingTrackId == track.id
    val laneClip = timelineClipsByTrackId[track.id]
    val localLaneLayoutDurationMs =
        laneClip?.let { timelineLaneLocalLayoutDurationMs(it) }
            ?: timelineLaneLayoutDurationMs
    val showsGlobalPlayhead =
        trackLaneShowsGlobalPlayhead(
            showWaveforms = showWaveforms,
            hasTimelineClip = laneClip != null,
        )

    @Composable
    fun RowTrackCard(
        recordingInputLevel: Float,
        timelinePlayheadPositionMs: Long,
        globalPlayheadTimelineDurationMs: Long,
        globalMixScopeDurationMs: Long,
        loopPlaybackActive: Boolean,
    ) {
        TrackCard(
            title = track.name ?: "Track",
            isSelected = selectedTrackIds.contains(track.id),
            isRecording = recordingTrackId == track.id,
            recordingInputLevel = recordingInputLevel,
            timelineClip = laneClip,
            laneLayoutDurationMs = localLaneLayoutDurationMs,
            globalMixScopeDurationMs = globalMixScopeDurationMs,
            globalPlayheadTimelineDurationMs = globalPlayheadTimelineDurationMs,
            timelinePlayheadPositionMs = timelinePlayheadPositionMs,
            loopPlaybackActive = loopPlaybackActive,
            gain = track.gain,
            onGainChange = { gain -> onGainChange(track.id, gain) },
            onGainCommit = { gain -> onGainCommit(track.id, gain) },
            gainFaderEnabled = gainFaderEnabled && track.importStatus == TrackImportStatus.READY,
            onGainDragStart = { onGainDragStart(track.id) },
            onGainDragEnd = { onGainDragEnd(track.id) },
            pan = track.pan,
            onPanChange = { pan -> onPanChange(track.id, pan) },
            onPanCommit = { pan -> onPanCommit(track.id, pan) },
            onClick = { onToggleSelect(track.id) },
            onDelete = { onDeleteTrack(track.id) },
            onCancelImport = { onCancelImport(track.id) },
            onRename = { onRenameTrack(track.id, it) },
            onEdit = { onEditTrack(track.id) },
            editEnabled = trackActionsEnabled && track.hasPersistedPlayableAudio(),
            onToggleRecordTarget = { onToggleRecordTarget(track.id) },
            isRecordTarget = recordTargetTrackId == track.id,
            recordTargetToggleEnabled =
                trackActionsEnabled &&
                    !importInProgress &&
                    track.importStatus == TrackImportStatus.READY,
            onToggleLoop = { onToggleLoop(track.id) },
            isLoop = track.isLoop,
            loopToggleEnabled = trackActionsEnabled && track.importStatus == TrackImportStatus.READY,
            loopRegionEditingEnabled =
                loopRegionEditingEnabled(
                    playbackActive = playbackActive,
                    recordingActive = recordingTrackId != null,
                ),
            onLoopRegionCommit = { startMs, endMs ->
                onUpdateTrackLoopRegion(track.id, startMs, endMs)
            },
            trackActionsEnabled = trackActionsEnabled,
            showWaveforms = showWaveforms,
            trackId = track.id,
            trackSlotHeight = trackLayout.trackSlotHeight,
            interactionBlocked = listInteractionLocked,
            blockReorderDrag =
                (reorderActive && dragController.draggingKey != track.id) ||
                    dropSettleInProgress,
            onReorderDragStart = { positionInRoot, cardBoundsInRoot ->
                val bounds = itemBoundsMap[track.id] ?: cardBoundsInRoot
                val offsetFromFinger = positionInRoot - Offset(bounds.left, bounds.top)
                val fixedXInParentPx = bounds.left - listParentBoundsInRoot.left
                dragController.start(
                    key = track.id,
                    startPos = positionInRoot,
                    offsetFromFingerToItemTopLeft = offsetFromFinger,
                    fixedXInParentPx = fixedXInParentPx,
                    overlayWidthPx = bounds.right - bounds.left,
                    overlayHeightPx = bounds.bottom - bounds.top,
                )
                onReorderDragStarted(track.id)
            },
            onReorderDragMove = { positionInRoot -> dragController.update(positionInRoot) },
            isMenuOpen = isMenuOpen,
            onMenuOpen = onMenuOpen,
            onMenuDismiss = onMenuDismiss,
        )
    }

    val isGhostRow =
        (reorderActive && dragController.draggingKey == track.id) ||
            dropSettlingTrackId == track.id

    @Composable
    fun RowTrackCardContent() {
        if (needsLiveMeter || showsGlobalPlayhead) {
            val realtime by realtimeUiState.collectAsStateWithLifecycle()
            val loopPlaybackActive =
                playbackActive &&
                    track.isLoop &&
                    track.id in selectedTrackIds
            RowTrackCard(
                recordingInputLevel = if (needsLiveMeter) realtime.recordingInputLevel else 0f,
                timelinePlayheadPositionMs =
                    if (showsGlobalPlayhead) realtime.playheadPositionMs else 0L,
                globalPlayheadTimelineDurationMs =
                    if (showsGlobalPlayhead) {
                        trackLaneGlobalOverlayTimelineDurationMs(
                            laneLayoutDurationMs = localLaneLayoutDurationMs,
                            rawPlayheadMs = realtime.playheadPositionMs,
                            timelineVisibleDurationMs = realtime.timelineVisibleDurationMs,
                        )
                    } else {
                        localLaneLayoutDurationMs
                    },
                globalMixScopeDurationMs = timelineLaneLayoutDurationMs,
                loopPlaybackActive = loopPlaybackActive,
            )
        } else {
            RowTrackCard(
                recordingInputLevel = 0f,
                timelinePlayheadPositionMs = 0L,
                globalPlayheadTimelineDurationMs = localLaneLayoutDurationMs,
                globalMixScopeDurationMs = timelineLaneLayoutDurationMs,
                loopPlaybackActive = false,
            )
        }
    }

    Box(
        modifier =
            Modifier
                .animateItem(
                    fadeInSpec = null,
                    fadeOutSpec = null,
                    placementSpec =
                        when {
                            incomingSlideSessionKey != null -> null
                            isGhostRow -> null
                            suppressRowPlacementAfterSettle -> null
                            else -> TrackRowPlacementSpec
                        },
                )
                .onGloballyPositioned { coords ->
                    val r = coords.boundsInRoot()
                    val id = track.id
                    val prev = itemBoundsMap[id]
                    if (prev == null || !prev.nearlyEqualsTo(r, ItemBoundsEpsilonPx)) {
                        itemBoundsMap[id] = r
                    }
                }
                .alpha(if (isGhostRow) 0f else 1f),
    ) {
        if (incomingSlideSessionKey != null) {
            PageSliceBottomIncomingSlide(
                sessionKey = incomingSlideSessionKey,
                enabled = true,
                slotDp = trackLayout.trackSlotHeight,
                spacingDp = trackLayout.listVerticalSpacing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RowTrackCardContent()
            }
        } else {
            RowTrackCardContent()
        }
    }
}
