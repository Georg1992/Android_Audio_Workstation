package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlin.math.roundToInt

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

    val menuShape = RoundedCornerShape(Dimens.TileRadius)
    val buttonShape = RoundedCornerShape(Dimens.MediumRadius)
    val itemShape = RoundedCornerShape(Dimens.MediumRadius)

    val gainClamped = gain.coerceIn(0f, 100f)
    val gainText = gainClamped.roundToInt().toString()

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
                            .size(Dimens.MenuButtonSize)
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

                    Column(
                        modifier = Modifier
                            .align(Alignment.End)
                            .wrapContentWidth()
                            .clip(menuShape)
                            .background(AppColors.Bg)
                            .border(Dimens.Stroke, AppColors.Line, menuShape)
                            .padding(Dimens.Stroke),
                        verticalArrangement = Arrangement.spacedBy(Dimens.Stroke)
                    ) {
                        MenuRowRight(
                            text = "Delete",
                            icon = {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = AppColors.Line
                                )
                            },
                            shape = itemShape
                        ) {
                            menuExpanded = false
                            onDelete()
                        }

                        MenuRowRight(
                            text = "Rename",
                            icon = {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = null,
                                    tint = AppColors.Line
                                )
                            },
                            shape = itemShape
                        ) {
                            menuExpanded = false
                            renameFieldValue = TextFieldValue(
                                text = title,
                                selection = TextRange(0, title.length)
                            )
                            renameFieldWasFocused = false
                            isRenaming = true
                        }
                    }
                }

                Spacer(Modifier.height(Dimens.PanelPadding))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.PlaceholderHeight)
                        .clip(RoundedCornerShape(Dimens.MediumRadius))
                        .background(AppColors.Bg)
                        .border(Dimens.Stroke, AppColors.Line, RoundedCornerShape(Dimens.MediumRadius))
                )
            }

            Spacer(Modifier.width(Dimens.Gap))

            // RIGHT: fader + number use as much vertical space as possible; number on bottom
            Column(
                modifier = Modifier
                    .width(Dimens.FaderWidth)
                    .heightIn(min = Dimens.FaderMinHeight)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Fader(
                    value = gainClamped,
                    onValueChange = { onGainChange(it.coerceIn(0f, 100f)) },
                    valueRange = 0f..100f,
                    enabled = !interactionBlocked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                )
                Text(
                    text = gainText,
                    color = AppColors.Line,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }

        if (showHandle) {
            var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Dimens.SmallRadius)
                    .size(Dimens.DragHandleSize)
                    .alpha(if (dragHandleEnabled) 1f else 0.35f)
                    .onGloballyPositioned { handleCoords = it }
                    .pointerInput(trackId, blockDragHandle, dragHandleEnabled) {
                        if (blockDragHandle || !dragHandleEnabled) {
                            while (true) {
                                awaitEachGesture {
                                    do {
                                        val event = awaitPointerEvent()
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                        } else {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val pos = handleCoords?.localToRoot(Offset(offset.x, offset.y))
                                    if (pos != null) onDragHandleStart(pos)
                                },
                                onDrag = { change, _ ->
                                    val pos = handleCoords?.localToRoot(Offset(change.position.x, change.position.y))
                                    if (pos != null) onDragHandleMove(pos)
                                },
                                onDragEnd = { onDragHandleEnd() },
                                onDragCancel = { onDragHandleEnd() }
                            )
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val color = AppColors.Line.copy(alpha = 0.85f)
                    val dotR = minOf(w, h) * 0.07f
                    listOf(0.72f to 0.92f, 0.84f to 0.84f, 0.92f to 0.72f).forEach { (tx, ty) ->
                        drawCircle(
                            color = color,
                            radius = dotR,
                            center = Offset(w * tx, h * ty)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRowRight(
    text: String,
    icon: @Composable () -> Unit,
    shape: RoundedCornerShape,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .wrapContentWidth()
            .heightIn(min = Dimens.MenuRowMinHeight)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.Gap, vertical = Dimens.SmallRadius),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        icon()
        Spacer(Modifier.width(Dimens.PanelPadding))
        Text(
            text = text,
            color = AppColors.Line,
            maxLines = 1
        )
    }
}
