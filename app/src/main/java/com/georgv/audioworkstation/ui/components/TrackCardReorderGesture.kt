package com.georgv.audioworkstation.ui.components

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
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
import androidx.compose.ui.input.pointer.PointerId
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
                            handleTrackCardLongPressGesture(
                                liftHandler = liftHandler,
                                preLiftSlopPx = preLiftSlopPx,
                                latestCoords = latestCoords,
                                view = view,
                                tapEnabled = tapEnabled,
                                onTap = latestOnTap,
                                onStart = latestOnStart,
                                onMove = latestOnMove,
                                onEnd = latestOnEnd,
                            )
                        }
                    }
                },
            )
    }

private suspend fun AwaitPointerEventScope.handleTrackCardLongPressGesture(
    liftHandler: Handler,
    preLiftSlopPx: Float,
    latestCoords: LayoutCoordinates?,
    view: android.view.View,
    tapEnabled: Boolean,
    onTap: () -> Unit,
    onStart: (positionInRoot: Offset, cardBoundsInRoot: Rect) -> Unit,
    onMove: (positionInRoot: Offset) -> Unit,
    onEnd: () -> Unit,
) {
    val down =
        awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Final,
        )
    if (down.isConsumed) {
        return
    }

    val env =
        TrackCardLongPressEnv(
            pointerId = down.id,
            startLocal = down.position,
            preLiftSlopPx = preLiftSlopPx,
            latestCoords = latestCoords,
            view = view,
            tapEnabled = tapEnabled,
            onTap = onTap,
            onStart = onStart,
            onMove = onMove,
            onEnd = onEnd,
        )
    val liftRunnable = Runnable { env.liftNow() }
    liftHandler.postDelayed(liftRunnable, TrackReorderLongPressMs)
    try {
        if (!awaitPreLiftCompletion(env)) {
            return
        }
        awaitPostLiftDrag(env)
    } finally {
        liftHandler.removeCallbacks(liftRunnable)
    }
}

private class TrackCardLongPressEnv(
    val pointerId: PointerId,
    val startLocal: Offset,
    val preLiftSlopPx: Float,
    val latestCoords: LayoutCoordinates?,
    val view: android.view.View,
    val tapEnabled: Boolean,
    val onTap: () -> Unit,
    val onStart: (positionInRoot: Offset, cardBoundsInRoot: Rect) -> Unit,
    val onMove: (positionInRoot: Offset) -> Unit,
    val onEnd: () -> Unit,
) {
    var lifted = false
    var lastLocal = startLocal

    fun rootPosition(local: Offset): Offset? = latestCoords?.localToRoot(local)

    fun cardBoundsInRoot(): Rect? = latestCoords?.boundsInRoot()

    fun movementExceededPreLiftSlop(local: Offset): Boolean =
        hypot(local.x - startLocal.x, local.y - startLocal.y) > preLiftSlopPx

    fun liftNow(): Boolean {
        if (lifted) return true
        val root = rootPosition(lastLocal) ?: return false
        val bounds = cardBoundsInRoot() ?: return false
        lifted = true
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onStart(root, bounds)
        onMove(root)
        return true
    }
}

private suspend fun AwaitPointerEventScope.awaitPreLiftCompletion(env: TrackCardLongPressEnv): Boolean {
    while (!env.lifted) {
        val event = awaitPointerEvent(pass = PointerEventPass.Final)
        val change = event.changes.firstOrNull { it.id == env.pointerId } ?: return false

        if (change.isConsumed) {
            return false
        }

        if (!change.pressed) {
            if (env.tapEnabled && !env.movementExceededPreLiftSlop(change.position)) {
                env.onTap()
            }
            return false
        }

        env.lastLocal = change.position
        if (env.movementExceededPreLiftSlop(env.lastLocal)) {
            return false
        }
    }
    return true
}

private suspend fun AwaitPointerEventScope.awaitPostLiftDrag(env: TrackCardLongPressEnv) {
    while (true) {
        val event = awaitPointerEvent(pass = PointerEventPass.Final)
        val change = event.changes.firstOrNull { it.id == env.pointerId } ?: break

        if (!change.pressed) {
            env.onEnd()
            break
        }

        env.lastLocal = change.position
        if (change.positionChanged()) {
            change.consume()
            env.rootPosition(env.lastLocal)?.let(env.onMove)
        }
    }
}
