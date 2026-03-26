package com.georgv.audioworkstation.ui.modifiers

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

fun Modifier.consumeAllPointers(enabled: Boolean = true): Modifier {
    if (!enabled) return this

    return pointerInput(Unit) {
        while (true) {
            awaitEachGesture {
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}
