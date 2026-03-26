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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

@Composable
fun TrackCard(
    title: String,
    isSelected: Boolean,
    isRecording: Boolean,
    gain: Float,
    onGainChange: (Float) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    trackId: String? = null,
    onDragHandleStart: ((positionInRoot: Offset) -> Unit)? = null,
    onDragHandleMove: ((positionInRoot: Offset) -> Unit)? = null,
    onDragHandleEnd: (() -> Unit)? = null,
    /** When true, card tap, menu, and fader do not respond. */
    interactionBlocked: Boolean = false,
    /** When true, the reorder handle consumes touches but does not start a drag (other row is being dragged). */
    blockDragHandle: Boolean = false,
    /** When false, reorder handle is dimmed and does not start a drag (e.g. row clipped by list). */
    dragHandleEnabled: Boolean = true
) {
    val cardShape = RoundedCornerShape(Dimens.TileRadius)
    val bg = when {
        isRecording -> AppColors.Red
        isSelected -> AppColors.Green
        else -> AppColors.Bg
    }

    var menuExpanded by remember(trackId) { mutableStateOf(false) }
    var isRenaming by remember(trackId) { mutableStateOf(false) }
    var renameFieldValue by remember(trackId, title) { mutableStateOf(TextFieldValue(title)) }
    var renameFieldWasFocused by remember(trackId) { mutableStateOf(false) }
    val focusRequester = remember(trackId) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val buttonShape = RoundedCornerShape(Dimens.MediumRadius)

    val showHandle = trackId != null && onDragHandleStart != null && onDragHandleMove != null && onDragHandleEnd != null

    LaunchedEffect(interactionBlocked) {
        if (interactionBlocked) {
            menuExpanded = false
            isRenaming = false
            renameFieldWasFocused = false
        }
    }

    LaunchedEffect(isRenaming) {
        if (isRenaming) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    fun commitRename() {
        if (!isRenaming) return
        isRenaming = false
        renameFieldWasFocused = false
        onRename?.invoke(renameFieldValue.text.trim())
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(bg)
            .border(Dimens.Stroke, AppColors.Line, cardShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !interactionBlocked && !isRenaming
            ) {
                if (menuExpanded) menuExpanded = false else onClick()
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(Dimens.TileInnerPadding),
            verticalAlignment = Alignment.Top
        ) {
            // LEFT
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRenaming) {
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

                    Box(
                        modifier = Modifier
                            .width(Dimens.MenuButtonSize)
                            .height(Dimens.MenuButtonSize)
                            .clip(buttonShape)
                            .border(Dimens.Stroke, AppColors.Line, buttonShape)
                            .clickable(enabled = !interactionBlocked && !isRenaming) { menuExpanded = !menuExpanded },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Track menu",
                            tint = AppColors.Line
                        )
                    }
                }

                if (menuExpanded) {
                    Spacer(Modifier.height(Dimens.PanelPadding))
                    TrackOverflowMenu(
                        modifier = Modifier
                            .align(Alignment.End),
                        onDelete = {
                            menuExpanded = false
                            onDelete()
                        },
                        onRename = {
                            menuExpanded = false
                            renameFieldValue = TextFieldValue(
                                text = title,
                                selection = TextRange(0, title.length)
                            )
                            renameFieldWasFocused = false
                            isRenaming = true
                        }
                    )
                }

                Spacer(Modifier.height(Dimens.PanelPadding))
                TrackWaveformPlaceholder()
            }

            Spacer(Modifier.width(Dimens.Gap))

            TrackGainSection(
                gain = gain,
                onGainChange = onGainChange,
                enabled = !interactionBlocked
            )
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
