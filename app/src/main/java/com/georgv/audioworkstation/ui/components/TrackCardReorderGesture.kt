package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.georgv.audioworkstation.ui.modifiers.consumeAllPointers
import kotlin.math.hypot

internal const val TrackReorderLongPressMs = 2000L

/**
 * Long-press then drag to reorder. Uses [PointerEventPass.Final] so interactive children
 * (buttons, fader, pan knob, loop editor, etc.) keep priority; unconsumed touches on
 * waveform, title, and other non-interactive chrome start reorder after [TrackReorderLongPressMs].
 * Short tap invokes [onTap] when the child did not consume the gesture.
 */
fun Modifier.trackCardLongPressReorderGesture(
    enabled: Boolean,
    blockReorderDrag: Boolean,
    tapEnabled: Boolean,
    onTap: () -> Unit,
    onReorderDragStart: (positionInRoot: Offset) -> Unit,
    onReorderDragMove: (positionInRoot: Offset) -> Unit,
    onReorderDragEnd: () -> Unit,
): Modifier =
    composed {
        var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val latestCoords by rememberUpdatedState(cardCoords)
        val latestOnTap by rememberUpdatedState(onTap)
        val latestOnStart by rememberUpdatedState(onReorderDragStart)
        val latestOnMove by rememberUpdatedState(onReorderDragMove)
        val latestOnEnd by rememberUpdatedState(onReorderDragEnd)

        this
            .onGloballyPositioned { cardCoords = it }
            .consumeAllPointers(enabled = enabled && blockReorderDrag)
            .then(
                if (!enabled || blockReorderDrag) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down =
                                awaitFirstDown(
                                    requireUnconsumed = true,
                                    pass = PointerEventPass.Final,
                                )
                            val pointerId = down.id
                            val startPosition = down.position
                            val longPressDeadlineUptimeMs = down.uptimeMillis + TrackReorderLongPressMs
                            val touchSlop = viewConfiguration.touchSlop
                            var reorderStarted = false

                            while (true) {
                                val event =
                                    awaitPointerEvent(pass = PointerEventPass.Final)
                                val change =
                                    event.changes.firstOrNull { it.id == pointerId } ?: break

                                if (!change.pressed) {
                                    if (reorderStarted) {
                                        latestOnEnd()
                                    } else if (
                                        tapEnabled &&
                                        hypot(
                                            change.position.x - startPosition.x,
                                            change.position.y - startPosition.y,
                                        ) <= touchSlop
                                    ) {
                                        latestOnTap()
                                    }
                                    break
                                }

                                if (!reorderStarted) {
                                    if (
                                        hypot(
                                            change.position.x - startPosition.x,
                                            change.position.y - startPosition.y,
                                        ) > touchSlop
                                    ) {
                                        break
                                    }
                                    if (change.uptimeMillis >= longPressDeadlineUptimeMs) {
                                        val coords = latestCoords ?: break
                                        reorderStarted = true
                                        change.consume()
                                        latestOnStart(coords.localToRoot(change.position))
                                    }
                                } else {
                                    val coords = latestCoords ?: break
                                    if (change.positionChanged()) {
                                        change.consume()
                                        latestOnMove(coords.localToRoot(change.position))
                                    }
                                }
                            }
                        }
                    }
                },
            )
    }
