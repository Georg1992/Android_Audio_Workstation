package com.georgv.audioworkstation.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
class TopBarAlertState internal constructor(
    private val durationMs: Long
) {
    var message: String? by mutableStateOf(null)
        private set

    private var clearJob: Job? = null

    fun show(scope: CoroutineScope, message: String) {
        clearJob?.cancel()
        this.message = message
        clearJob = scope.launch {
            delay(durationMs)
            if (this@TopBarAlertState.message == message) {
                this@TopBarAlertState.message = null
            }
            clearJob = null
        }
    }
}

@Composable
fun rememberTopBarAlertState(
    durationMs: Long = 3_000
): TopBarAlertState = remember(durationMs) { TopBarAlertState(durationMs) }
