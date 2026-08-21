package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.core.timeline.TimelineMinimumBaseDurationMs
import com.georgv.audioworkstation.ui.modifiers.consumeAllPointers
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
    globalMixScopeDurationMs: Long = laneLayoutDurationMs,
    globalPlayheadTimelineDurationMs: Long = TimelineMinimumBaseDurationMs,
    timelinePlayheadPositionMs: Long = 0L,
    mixPlayheadPositionMs: Long = timelinePlayheadPositionMs,
    loopPlaybackActive: Boolean = false,
    gain: Float,
    onGainChange: ((Float) -> Unit)?,
    onGainCommit: ((Float) -> Unit)? = null,
    gainFaderEnabled: Boolean = true,
    onGainDragStart: (() -> Unit)? = null,
    onGainDragEnd: (() -> Unit)? = null,
    pan: Float = 0f,
    onPanChange: ((Float) -> Unit)? = null,
    onPanCommit: ((Float) -> Unit)? = null,
    /** When false, the timeline lane stays hidden until the project screen is ready to reveal. */
    showWaveforms: Boolean = true,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onCancelImport: () -> Unit = onDelete,
    onRename: ((String) -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    editEnabled: Boolean = false,
    onToggleLoop: (() -> Unit)? = null,
    isLoop: Boolean = false,
    loopToggleEnabled: Boolean = true,
    loopRegionEditingEnabled: Boolean = false,
    onLoopRegionCommit: ((loopStartMs: Long, loopEndMs: Long) -> Unit)? = null,
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
    onReorderDragStart: ((positionInRoot: Offset, cardBoundsInRoot: Rect) -> Unit)? = null,
    onReorderDragMove: ((positionInRoot: Offset) -> Unit)? = null,
    /** When true, card tap, menu, and fader do not respond. */
    interactionBlocked: Boolean = false,
    /** When true, card consumes touches but does not start a drag (other row is being dragged). */
    blockReorderDrag: Boolean = false,
    /**
     * Non-interactive overlay: same chrome and layout density as a normal card, without gestures.
     */
    dragPreview: Boolean = false,
    isMenuOpen: Boolean = false,
    onMenuOpen: () -> Unit = {},
    onMenuDismiss: () -> Unit = {},
) {
    val importStatus = timelineClip?.importStatus ?: TrackImportStatus.READY
    val isImporting = importStatus == TrackImportStatus.IMPORTING
    val isImportFailed = importStatus == TrackImportStatus.FAILED
    val importProgress =
        when (val waveformState = timelineClip?.waveformState) {
            is WaveformState.Importing -> waveformState.progress
            else -> timelineClip?.importProgress ?: 0f
        }
    val cardShape = RoundedCornerShape(Dimens.TileRadius)
    val selectionAllowed = !isImporting
    val showSelected = isSelected && selectionAllowed
    val bg =
        when {
            isRecording -> AppColors.Red
            showSelected -> AppColors.Green
            else -> AppColors.SurfacePanel
        }
    val borderColor =
        when {
            isImporting -> AppColors.Cyan
            isImportFailed -> AppColors.Red
            else -> AppColors.Line
        }
    val borderWidth = if (isImporting || isImportFailed) 2.dp else Dimens.Stroke

    var isRenaming by remember(trackId) { mutableStateOf(false) }
    var renameFieldValue by remember(trackId, title) { mutableStateOf(TextFieldValue(title)) }
    var renameFieldWasFocused by remember(trackId) { mutableStateOf(false) }
    val focusRequester = remember(trackId) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val reorderGestureEnabled =
        !dragPreview &&
            trackId != null &&
            onReorderDragStart != null &&
            onReorderDragMove != null
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
    var laneWaveformBoundsInRoot by remember(trackId) { mutableStateOf<Rect?>(null) }
    val latestLaneWaveformBoundsInRoot by rememberUpdatedState(laneWaveformBoundsInRoot)
    LaunchedEffect(isLoop) {
        if (!isLoop) {
            laneWaveformBoundsInRoot = null
        }
    }
    val loopLatchedDuringPlayback = isLoop && loopPlaybackActive
    val cardSelectionClickEnabled =
        selectionAllowed && !interactionBlocked && !isRenaming && !dragPreview
    val cardSelectionOnRoot =
        cardSelectionClickEnabled && !isolateTimelineTouch
    val reorderActiveOnCard =
        reorderGestureEnabled &&
            !blockReorderDrag &&
            !isImporting &&
            !isImportFailed
    val isolateTimelineSelectionViaClickable =
        isolateTimelineTouch && cardSelectionClickEnabled && !reorderActiveOnCard
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
                .border(borderWidth, borderColor, cardShape)
                .consumeAllPointers(enabled = reorderGestureEnabled && blockReorderDrag)
                .then(
                    if (reorderActiveOnCard) {
                        Modifier.trackCardLongPressReorderGesture(
                            enabled = true,
                            blockReorderDrag = false,
                            tapEnabled = cardSelectionClickEnabled,
                            onTap = onCardSelectionClick,
                            onReorderDragStart = onReorderDragStart,
                            onReorderDragMove = onReorderDragMove,
                            ignoreDownInRoot = { root ->
                                trackReorderIgnoresDownInWaveform(
                                    isLoopEnabled = isLoop,
                                    downPositionInRoot = root,
                                    laneWaveformBoundsInRoot = latestLaneWaveformBoundsInRoot,
                                )
                            },
                        )
                    } else if (!dragPreview && cardSelectionOnRoot) {
                        Modifier.clickable(
                            interactionSource = cardSelectionInteractionSource,
                            indication = null,
                            enabled = cardSelectionClickEnabled,
                            onClick = onCardSelectionClick,
                        )
                    } else {
                        Modifier
                    },
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
                modifier =
                    Modifier
                        .weight(1f)
                        .then(
                            if (trackSlotHeight != null) {
                                Modifier.fillMaxHeight()
                            } else {
                                Modifier
                            },
                        ),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .alpha(if (isImporting) 0.72f else 1f)
                            .then(
                                if (isolateTimelineSelectionViaClickable) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onCardSelectionClick,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    verticalAlignment = Alignment.Bottom,
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
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (isImporting) {
                        Spacer(Modifier.width(Dimens.IconGlowSpacing))
                        ImportStatusBadge(
                            text = stringResource(R.string.import_status_importing),
                            color = AppColors.Cyan,
                            progress = importProgress,
                        )
                        Spacer(Modifier.width(Dimens.IconGlowSpacing))
                    } else if (isImportFailed) {
                        Spacer(Modifier.width(Dimens.IconGlowSpacing))
                        ImportStatusBadge(
                            text = stringResource(R.string.import_status_failed),
                            color = AppColors.Red,
                        )
                        Spacer(Modifier.width(Dimens.IconGlowSpacing))
                    }

                    if (!isRenaming) {
                        TrackPanKnob(
                            pan = pan,
                            onPanChange = if (dragPreview) null else onPanChange,
                            onPanCommit = if (dragPreview) null else onPanCommit,
                            enabled =
                                !dragPreview &&
                                    !interactionBlocked &&
                                    onPanChange != null,
                        )
                        Spacer(Modifier.width(Dimens.IconGlowSpacing))
                    }

                    if (showRecordTargetChrome) {
                        val toggleRecordTarget = onToggleRecordTarget
                        val recordTargetInteractive =
                            !dragPreview &&
                                toggleRecordTarget != null &&
                                recordTargetToggleEnabled &&
                                trackActionsEnabled &&
                                !interactionBlocked &&
                                !isRenaming &&
                                !isImporting
                        TrackActionButton(
                            active = isRecordTarget,
                            enabled = recordTargetInteractive,
                            accentColor = AppColors.Red,
                            activeBorderColor = AppColors.Line,
                            onClick = { toggleRecordTarget?.invoke() },
                        ) { iconTint ->
                            RecordTargetButtonIcon(
                                active = isRecordTarget,
                                tint = iconTint,
                                contentDescription =
                                    stringResource(
                                        if (isRecordTarget) {
                                            R.string.cd_record_target_on
                                        } else {
                                            R.string.cd_record_target_off
                                        }
                                    ),
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
                        TrackActionButton(
                            active = isLoop,
                            enabled = loopInteractive,
                            preserveOpacityWhenDisabled = loopLatchedDuringPlayback,
                            accentColor = AppColors.Green,
                            activeBackgroundColor = AppColors.LoopButtonActiveBackground,
                            activeBorderColor = AppColors.Line,
                            activeIconColor = AppColors.Line,
                            showActiveGlow = isLoop,
                            showActiveLed = isLoop,
                            onClick = { toggleLoop?.invoke() },
                        ) { iconTint ->
                            Icon(
                                imageVector = Icons.Filled.Loop,
                                contentDescription = stringResource(
                                    if (isLoop) R.string.cd_loop_on else R.string.cd_loop_off
                                ),
                                tint = iconTint,
                            )
                        }
                        Spacer(Modifier.width(Dimens.IconGlowSpacing))
                    }

                    val menuDropdownShape = RoundedCornerShape(Dimens.TileRadius)
                    Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                        TrackActionButton(
                            active = false,
                            enabled =
                                !trackMenuClickDisabled(
                                    dragPreview,
                                    interactionBlocked,
                                    trackActionsEnabled,
                                    isRenaming,
                                ),
                            accentColor = AppColors.Accent,
                            backgroundOverride =
                                if (!dragPreview && trackActionsEnabled && isMenuOpen) {
                                    AppColors.SurfacePressed
                                } else {
                                    null
                                },
                            borderColor = AppColors.Line,
                            idleIconColor = AppColors.Line,
                            showActiveGlow = false,
                            showActiveLed = false,
                            onClick = {
                                if (isMenuOpen) {
                                    onMenuDismiss()
                                } else {
                                    onMenuOpen()
                                }
                            },
                        ) { iconTint ->
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.cd_track_menu),
                                tint = iconTint,
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
                                isImporting = isImporting,
                                onDelete = {
                                    onMenuDismiss()
                                    onDelete()
                                },
                                onCancelImport = {
                                    onMenuDismiss()
                                    onCancelImport()
                                },
                                onEdit =
                                    if (editEnabled && onEdit != null) {
                                        {
                                            onMenuDismiss()
                                            onEdit()
                                        }
                                    } else {
                                        null
                                    },
                                editEnabled = editEnabled,
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
                when {
                    timelineClip != null && showWaveforms -> {
                        TrackTimelineLane(
                            clip = timelineClip,
                            laneLayoutDurationMs = laneLayoutDurationMs,
                            globalPlayheadTimelineDurationMs = globalPlayheadTimelineDurationMs,
                            globalMixScopeDurationMs = globalMixScopeDurationMs,
                            playheadPositionMs = timelinePlayheadPositionMs,
                            mixPlayheadPositionMs = mixPlayheadPositionMs,
                            loopPlaybackActive = loopPlaybackActive,
                            recordingInputLevel = if (isRecording) recordingInputLevel else null,
                            loopRegionEditingEnabled = loopRegionEditingEnabled,
                            onLoopRegionCommit = onLoopRegionCommit,
                            onLoopWaveformContainerBoundsInRoot =
                                if (isLoop) {
                                    { bounds -> laneWaveformBoundsInRoot = bounds }
                                } else {
                                    null
                                },
                            modifier = waveformModifier,
                        )
                    }
                    isRecording -> {
                        RecordingWaveform(
                            inputLevel = recordingInputLevel,
                            modifier = waveformModifier,
                        )
                    }
                    else -> {
                        WaveformEmptyPlaceholder(modifier = waveformModifier)
                    }
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
                                if (isolateTimelineSelectionViaClickable) {
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
                        enabled =
                            !dragPreview &&
                                !interactionBlocked &&
                                onGainChange != null &&
                                gainFaderEnabled,
                        onGainDragStart = if (dragPreview) null else onGainDragStart,
                        onGainDragEnd = if (dragPreview) null else onGainDragEnd,
                        fillTrackHeight = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                TrackGainSection(
                    gain = gain,
                    onGainChange = if (dragPreview) null else onGainChange,
                    onGainCommit = if (dragPreview) null else onGainCommit,
                    enabled =
                        !dragPreview &&
                            !interactionBlocked &&
                            onGainChange != null &&
                            gainFaderEnabled,
                    onGainDragStart = if (dragPreview) null else onGainDragStart,
                    onGainDragEnd = if (dragPreview) null else onGainDragEnd,
                )
            }
        }
    }
}

private fun trackMenuClickDisabled(
    dragPreview: Boolean,
    interactionBlocked: Boolean,
    trackActionsEnabled: Boolean,
    isRenaming: Boolean,
): Boolean = dragPreview || interactionBlocked || !trackActionsEnabled || isRenaming

@Composable
internal fun ImportStatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    val shape = RoundedCornerShape(Dimens.SmallRadius)
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { Dimens.SmallRadius.toPx() }
    val badgeHeight = Dimens.TrackHeaderButtonSize
    val labelStyle =
        TextStyle(
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
        )
    if (progress == null) {
        Box(
            modifier =
                modifier
                    .height(badgeHeight)
                    .background(color.copy(alpha = 0.28f), shape)
                    .border(Dimens.Stroke, color, shape)
                    .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = AppColors.Line,
                style = labelStyle,
            )
        }
        return
    }

    val clampedProgress = progress.coerceIn(0f, 1f)
    val percent = (clampedProgress * 100f).roundToInt()
    val displayText = "$text $percent%"
    val maxWidthLabel = "$text 100%"
    Box(
        modifier =
            modifier
                .height(badgeHeight)
                .drawBehind {
                    drawRoundRect(
                        color = color.copy(alpha = 0.28f),
                        cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    )
                    if (clampedProgress > 0f) {
                        drawRoundRect(
                            color = color,
                            size = size.copy(width = size.width * clampedProgress),
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        )
                    }
                }
                .border(Dimens.Stroke, color, shape)
                .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = maxWidthLabel,
            style = labelStyle,
            modifier = Modifier.alpha(0f),
        )
        Text(
            text = displayText,
            color = AppColors.Line,
            style = labelStyle,
        )
    }
}

/** Lightweight empty waveform slot — no skeleton bars. */
@Composable
internal fun WaveformEmptyPlaceholder(
    modifier: Modifier = Modifier,
    statusText: String? = null,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(Dimens.MediumRadius))
                .background(AppColors.SurfacePanel),
        contentAlignment = Alignment.CenterStart,
    ) {
        statusText?.let { text ->
            Text(
                text = text,
                color = AppColors.textSecondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp),
            )
        }
    }
}
