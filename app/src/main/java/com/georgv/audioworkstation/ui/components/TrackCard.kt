package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

@Composable
fun TrackCard(
    title: String,
    isSelected: Boolean,
    isRecording: Boolean,
    recordingInputLevel: Float = 0f,
    timelineClip: TimelineClip? = null,
    laneLayoutDurationMs: Long = TimelineMinimumBaseDurationMs,
    globalPlayheadTimelineDurationMs: Long = TimelineMinimumBaseDurationMs,
    timelinePlayheadPositionMs: Long = 0L,
    gain: Float,
    onGainChange: ((Float) -> Unit)?,
    onGainCommit: ((Float) -> Unit)? = null,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: ((String) -> Unit)? = null,
    onToggleLoop: (() -> Unit)? = null,
    isLoop: Boolean = false,
    loopToggleEnabled: Boolean = true,
    loopRegionEditingEnabled: Boolean = false,
    onLoopRegionCommit: ((loopStartMs: Long, loopEndMs: Long) -> Unit)? = null,
    hasPersistedPlayableAudio: Boolean = false,
    onToggleRecordTarget: (() -> Unit)? = null,
    isRecordTarget: Boolean = false,
    recordTargetToggleEnabled: Boolean = true,
    trackActionsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    /**
     * When set (project track list policy), row height matches a computed viewport slot so a
     * screenful fits whole cards. When null, height follows intrinsic fader + chrome.
     */
    trackSlotHeight: Dp? = null,
    trackId: String? = null,
    onDragHandleStart: ((positionInRoot: Offset) -> Unit)? = null,
    onDragHandleMove: ((positionInRoot: Offset) -> Unit)? = null,
    onDragHandleEnd: (() -> Unit)? = null,
    /** When true, card tap, menu, and fader do not respond. */
    interactionBlocked: Boolean = false,
    /** When true, the reorder handle consumes touches but does not start a drag (other row is being dragged). */
    blockDragHandle: Boolean = false,
    /** When false, reorder handle is dimmed and does not start a drag (e.g. row clipped by list). */
    dragHandleEnabled: Boolean = true,
    /**
     * Non-interactive overlay: same chrome and layout density as a normal card, without gestures.
     */
    dragPreview: Boolean = false,
    isMenuOpen: Boolean = false,
    onMenuOpen: () -> Unit = {},
    onMenuDismiss: () -> Unit = {},
) {
    val cardShape = RoundedCornerShape(Dimens.TileRadius)
    val bg = when {
        isRecording -> AppColors.Red
        isSelected -> AppColors.Green
        else -> AppColors.SurfacePanel
    }

    var isRenaming by remember(trackId) { mutableStateOf(false) }
    var renameFieldValue by remember(trackId, title) { mutableStateOf(TextFieldValue(title)) }
    var renameFieldWasFocused by remember(trackId) { mutableStateOf(false) }
    val focusRequester = remember(trackId) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val buttonShape = RoundedCornerShape(Dimens.MediumRadius)

    val showHandle =
        !dragPreview && trackId != null && onDragHandleStart != null && onDragHandleMove != null && onDragHandleEnd != null
    val showLoopChrome = dragPreview || onToggleLoop != null
    val showRecordTargetChrome = dragPreview || onToggleRecordTarget != null

    LaunchedEffect(interactionBlocked, dragPreview, trackActionsEnabled) {
        if (interactionBlocked || dragPreview || !trackActionsEnabled) {
            onMenuDismiss()
            isRenaming = false
            renameFieldWasFocused = false
        }
    }

    LaunchedEffect(isRenaming) {
        if (!dragPreview && isRenaming) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    fun commitRename() {
        if (dragPreview || !isRenaming) return
        isRenaming = false
        renameFieldWasFocused = false
        onRename?.invoke(renameFieldValue.text)
    }

    val isolateTimelineTouch = isLoop && timelineClip != null && !dragPreview
    val cardSelectionClickEnabled = !interactionBlocked && !isRenaming && !dragPreview
    val cardSelectionInteractionSource = remember { MutableInteractionSource() }
    val onCardSelectionClick = {
        if (isMenuOpen) {
            onMenuDismiss()
        } else {
            onClick()
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.TrackCardOuterPaddingHorizontal)
                .then(
                    if (trackSlotHeight != null) {
                        Modifier.height(trackSlotHeight)
                    } else {
                        Modifier
                    }
                )
                .clip(cardShape)
                .background(bg)
                .border(Dimens.Stroke, AppColors.Line, cardShape)
                .then(
                    if (dragPreview || isolateTimelineTouch) {
                        Modifier
                    } else {
                        Modifier.clickable(
                            interactionSource = cardSelectionInteractionSource,
                            indication = null,
                            enabled = cardSelectionClickEnabled,
                            onClick = onCardSelectionClick,
                        )
                    }
                )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (trackSlotHeight != null) {
                        Modifier.fillMaxHeight()
                    } else {
                        Modifier.height(IntrinsicSize.Min)
                    }
                )
                .padding(
                    horizontal =
                        (Dimens.TileInnerPadding - Dimens.TrackCardOuterPaddingHorizontal)
                            .coerceAtLeast(0.dp),
                    vertical = Dimens.TileInnerPadding,
                ),
            verticalAlignment = Alignment.Top
        ) {
            // LEFT
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (trackSlotHeight != null) {
                            Modifier.fillMaxHeight()
                        } else {
                            Modifier
                        }
                    )
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (isolateTimelineTouch && cardSelectionClickEnabled) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onCardSelectionClick,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!dragPreview && isRenaming) {
                        TextField(
                            value = renameFieldValue,
                            onValueChange = { renameFieldValue = it },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        renameFieldWasFocused = true
                                    } else if (renameFieldWasFocused) {
                                        commitRename()
                                    }
                                },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    commitRename()
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                }
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = AppColors.Bg,
                                unfocusedContainerColor = AppColors.Bg,
                                disabledContainerColor = AppColors.Bg,
                                focusedTextColor = AppColors.Line,
                                unfocusedTextColor = AppColors.Line,
                                focusedIndicatorColor = AppColors.Line,
                                unfocusedIndicatorColor = AppColors.Line,
                                cursorColor = AppColors.Line
                            )
                        )
                    } else {
                        Text(
                            text = title,
                            color = AppColors.Line,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (showRecordTargetChrome) {
                        val toggleRecordTarget = onToggleRecordTarget
                        val recordTargetInteractive =
                            !dragPreview &&
                                toggleRecordTarget != null &&
                                recordTargetToggleEnabled &&
                                trackActionsEnabled &&
                                !interactionBlocked &&
                                !isRenaming
                        Box(
                            modifier = Modifier
                                .width(Dimens.TrackHeaderButtonSize)
                                .height(Dimens.TrackHeaderButtonSize)
                                .then(
                                    if (isRecordTarget) Modifier.glow(
                                        color = AppColors.Red,
                                        blurRadius = Dimens.GlowBlur,
                                        cornerRadius = Dimens.MediumRadius
                                    ) else Modifier
                                )
                                .clip(buttonShape)
                                .alpha(if ((recordTargetToggleEnabled && trackActionsEnabled) || dragPreview) 1f else 0.45f)
                                .background(if (isRecordTarget) AppColors.Red else AppColors.Bg)
                                .border(Dimens.Stroke, AppColors.Line, buttonShape)
                                .then(
                                    if (recordTargetInteractive) {
                                        Modifier.clickable { toggleRecordTarget.invoke() }
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FiberManualRecord,
                                contentDescription = stringResource(
                                    if (isRecordTarget) R.string.cd_record_target_on else R.string.cd_record_target_off
                                ),
                                tint = if (isRecordTarget) AppColors.Line else AppColors.Line
                            )
                        }
                        Spacer(Modifier.width(Dimens.IconGlowSpacing))
                    }

                    if (showLoopChrome) {
                        val toggleLoop = onToggleLoop
                        val loopInteractive =
                            !dragPreview &&
                                toggleLoop != null &&
                                loopToggleEnabled &&
                                trackActionsEnabled &&
                                !interactionBlocked &&
                                !isRenaming
                        Box(
                            modifier = Modifier
                                .width(Dimens.TrackHeaderButtonSize)
                                .height(Dimens.TrackHeaderButtonSize)
                                .then(
                                    if (isLoop) Modifier.glow(
                                        color = AppColors.Accent,
                                        blurRadius = Dimens.GlowBlur,
                                        cornerRadius = Dimens.MediumRadius
                                    ) else Modifier
                                )
                                .clip(buttonShape)
                                .alpha(if ((loopToggleEnabled && trackActionsEnabled) || dragPreview) 1f else 0.45f)
                                .background(if (isLoop) AppColors.Accent else AppColors.Bg)
                                .border(Dimens.Stroke, AppColors.Line, buttonShape)
                                .then(
                                    if (loopInteractive) {
                                        Modifier.clickable { toggleLoop.invoke() }
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Loop,
                                contentDescription = stringResource(
                                    if (isLoop) R.string.cd_loop_on else R.string.cd_loop_off
                                ),
                                tint = if (isLoop) AppColors.Red else AppColors.Line
                            )
                        }
                        Spacer(Modifier.width(Dimens.IconGlowSpacing))
                    }

                    val menuDropdownShape = RoundedCornerShape(Dimens.TileRadius)
                    Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                        Box(
                            modifier =
                                Modifier
                                    .width(Dimens.TrackHeaderButtonSize)
                                    .height(Dimens.TrackHeaderButtonSize)
                                    .then(
                                        if (!dragPreview && trackActionsEnabled && isMenuOpen) {
                                            Modifier.glow(
                                                color = AppColors.Accent,
                                                blurRadius = Dimens.GlowBlur,
                                                cornerRadius = Dimens.MediumRadius
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clip(buttonShape)
                                    .alpha(if (trackActionsEnabled || dragPreview) 1f else 0.45f)
                                    .background(
                                        if (!dragPreview && trackActionsEnabled && isMenuOpen) {
                                            AppColors.Accent
                                        } else {
                                            AppColors.Bg
                                        }
                                    )
                                    .border(Dimens.Stroke, AppColors.Line, buttonShape)
                                    .then(
                                        if (trackMenuClickDisabled(
                                                dragPreview,
                                                interactionBlocked,
                                                trackActionsEnabled,
                                                isRenaming,
                                            )
                                        ) {
                                            Modifier
                                        } else {
                                            Modifier.clickable {
                                                if (isMenuOpen) {
                                                    onMenuDismiss()
                                                } else {
                                                    onMenuOpen()
                                                }
                                            }
                                        }
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.cd_track_menu),
                                tint =
                                    if (!dragPreview && trackActionsEnabled && isMenuOpen) {
                                        AppColors.Red
                                    } else {
                                        AppColors.Line
                                    }
                            )
                        }
                        DropdownMenu(
                            expanded =
                                !dragPreview &&
                                    trackActionsEnabled &&
                                    !interactionBlocked &&
                                    !isRenaming &&
                                    isMenuOpen,
                            onDismissRequest = onMenuDismiss,
                            modifier =
                                Modifier.border(
                                    Dimens.Stroke,
                                    AppColors.Line,
                                    menuDropdownShape,
                                ),
                            shape = menuDropdownShape,
                            containerColor = AppColors.Bg,
                            tonalElevation = 0.dp,
                        ) {
                            TrackOverflowMenuBody(
                                modifier = Modifier.padding(Dimens.Stroke),
                                onDelete = {
                                    onMenuDismiss()
                                    onDelete()
                                },
                                onRename = {
                                    onMenuDismiss()
                                    renameFieldValue =
                                        TextFieldValue(
                                            text = title,
                                            selection = TextRange(0, title.length)
                                        )
                                    renameFieldWasFocused = false
                                    isRenaming = true
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Dimens.PanelPadding))
                val waveformModifier =
                    if (trackSlotHeight != null) {
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .fillMaxHeight()
                    } else {
                        Modifier.fillMaxWidth()
                    }
                val cardRenderBranch =
                    when {
                        timelineClip != null -> "timeline_lane"
                        isRecording -> "fallback_live_recording"
                        else -> "fallback_empty_waveform"
                    }
                LoopUiLogTrackCardOnChange(
                    snapshot =
                        LoopUiTrackCardSnapshot(
                            trackId = trackId,
                            isLoop = isLoop,
                            isRecording = isRecording,
                            hasTimelineClip = timelineClip != null,
                            hasPersistedPlayableAudio = hasPersistedPlayableAudio,
                            recordingInputLevelPresent = isRecording,
                            renderBranch = cardRenderBranch,
                            loopRegionEditingEnabled = loopRegionEditingEnabled,
                        ),
                )
                if (timelineClip == null) {
                    if (isRecording) {
                        RecordingWaveform(
                            inputLevel = recordingInputLevel,
                            modifier = waveformModifier,
                        )
                    } else {
                        TrackWaveform(modifier = waveformModifier)
                    }
                } else {
                    TrackTimelineLane(
                        clip = timelineClip,
                        laneLayoutDurationMs = laneLayoutDurationMs,
                        globalPlayheadTimelineDurationMs = globalPlayheadTimelineDurationMs,
                        playheadPositionMs = timelinePlayheadPositionMs,
                        recordingInputLevel = recordingInputLevel.takeIf { isRecording },
                        loopRegionEditingEnabled = loopRegionEditingEnabled,
                        onLoopRegionCommit = onLoopRegionCommit,
                        trackId = trackId,
                        hasPersistedPlayableAudio = hasPersistedPlayableAudio,
                        modifier = waveformModifier,
                    )
                }
            }

            Spacer(Modifier.width(Dimens.Gap))

            if (trackSlotHeight != null) {
                Box(
                    modifier =
                        Modifier
                            .width(Dimens.FaderWidth)
                            .fillMaxHeight()
                            .then(
                                if (isolateTimelineTouch && cardSelectionClickEnabled) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onCardSelectionClick,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                ) {
                    TrackGainSection(
                        gain = gain,
                        onGainChange = if (dragPreview) null else onGainChange,
                        onGainCommit = if (dragPreview) null else onGainCommit,
                        enabled = !dragPreview && !interactionBlocked && onGainChange != null,
                        fillTrackHeight = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                TrackGainSection(
                    gain = gain,
                    onGainChange = if (dragPreview) null else onGainChange,
                    onGainCommit = if (dragPreview) null else onGainCommit,
                    enabled = !dragPreview && !interactionBlocked && onGainChange != null,
                )
            }
        }

        if (showHandle) {
            TrackReorderHandle(
                trackId = trackId,
                blockDragHandle = blockDragHandle,
                dragHandleEnabled = dragHandleEnabled,
                onDragHandleStart = onDragHandleStart,
                onDragHandleMove = onDragHandleMove,
                onDragHandleEnd = onDragHandleEnd,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

private fun trackMenuClickDisabled(
    dragPreview: Boolean,
    interactionBlocked: Boolean,
    trackActionsEnabled: Boolean,
    isRenaming: Boolean,
): Boolean = dragPreview || interactionBlocked || !trackActionsEnabled || isRenaming
