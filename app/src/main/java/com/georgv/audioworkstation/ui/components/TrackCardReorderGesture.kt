package com.georgv.audioworkstation.ui.components

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import com.georgv.audioworkstation.ui.modifiers.consumeAllPointers
import kotlin.math.hypot

internal const val TrackReorderLongPressMs = 2000L

/** Movement above this cancels a pre-lift hold (does not cancel after lift). */
private const val TrackReorderPreLiftSlopDp = 12f

/**
 * Short tap ([onTap]) vs [TrackReorderLongPressMs] hold-to-lift reorder on non-interactive chrome.
 * Uses [PointerEventPass.Final]; ignores downs already consumed by buttons, fader, pan, etc.
 */
fun Modifier.trackCardLongPressReorderGesture(
    enabled: Boolean,
    blockReorderDrag: Boolean,
    tapEnabled: Boolean,
    onTap: () -> Unit,
    onReorderDragStart: (positionInRoot: Offset, cardBoundsInRoot: Rect) -> Unit,
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
        val view = LocalView.current

        this
            .onGloballyPositioned { cardCoords = it }
            .consumeAllPointers(enabled = enabled && blockReorderDrag)
            .then(
                if (!enabled || blockReorderDrag) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        val liftHandler = Handler(Looper.getMainLooper())
                        val preLiftSlopPx = TrackReorderPreLiftSlopDp * density
                        awaitEachGesture {
                            val down =
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Final,
                                )
                            if (down.isConsumed) {
                                return@awaitEachGesture
                            }

                            val pointerId = down.id
                            val startLocal = down.position
                            var lifted = false
                            var lastLocal = startLocal

                            fun rootPosition(local: Offset): Offset? =
                                latestCoords?.localToRoot(local)

                            fun cardBoundsInRoot(): Rect? =
                                latestCoords?.boundsInRoot()

                            fun movementExceededPreLiftSlop(local: Offset): Boolean =
                                hypot(local.x - startLocal.x, local.y - startLocal.y) >
                                    preLiftSlopPx

                            fun liftNow(): Boolean {
                                if (lifted) return true
                                val root = rootPosition(lastLocal) ?: return false
                                val bounds = cardBoundsInRoot() ?: return false
                                lifted = true
                                view.performHapticFeedback(
                                    HapticFeedbackConstants.CONFIRM,
                                )
                                latestOnStart(root, bounds)
                                latestOnMove(root)
                                return true
                            }

                            val liftRunnable = Runnable { liftNow() }
                            liftHandler.postDelayed(liftRunnable, TrackReorderLongPressMs)
                            try {
                                while (!lifted) {
                                    val event =
                                        awaitPointerEvent(pass = PointerEventPass.Final)
                                    val change =
                                        event.changes.firstOrNull { it.id == pointerId }
                                            ?: break

                                    if (change.isConsumed) {
                                        return@awaitEachGesture
                                    }

                                    if (!change.pressed) {
                                        if (
                                            tapEnabled &&
                                            !movementExceededPreLiftSlop(change.position)
                                        ) {
                                            latestOnTap()
                                        }
                                        return@awaitEachGesture
                                    }

                                    lastLocal = change.position
                                    if (movementExceededPreLiftSlop(lastLocal)) {
                                        return@awaitEachGesture
                                    }
                                }

                                while (true) {
                                    val event =
                                        awaitPointerEvent(pass = PointerEventPass.Final)
                                    val change =
                                        event.changes.firstOrNull { it.id == pointerId }
                                            ?: break

                                    if (!change.pressed) {
                                        latestOnEnd()
                                        break
                                    }

                                    lastLocal = change.position
                                    if (change.positionChanged()) {
                                        change.consume()
                                        rootPosition(lastLocal)?.let(latestOnMove)
                                    }
                                }
                            } finally {
                                liftHandler.removeCallbacks(liftRunnable)
                            }
                        }
                    }
                },
            )
    }
