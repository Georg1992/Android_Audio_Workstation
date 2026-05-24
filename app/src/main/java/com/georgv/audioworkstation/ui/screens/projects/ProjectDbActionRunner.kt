package com.georgv.audioworkstation.ui.screens.projects

import androidx.annotation.StringRes
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.util.logWarning
import kotlinx.coroutines.CancellationException

internal class ProjectDbActionRunner(
    private val logTag: String,
    private val emitMessage: (UiMessage) -> Unit,
) {
    suspend fun run(
        @StringRes errorResId: Int,
        action: suspend () -> Unit,
    ): Boolean {
        return try {
            action()
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            logWarning(logTag, "database action failed", error)
            emitMessage(UiMessage(errorResId))
            false
        }
    }

    suspend fun runWithRollback(
        @StringRes errorResId: Int,
        rollback: () -> Unit,
        action: suspend () -> Unit,
    ) {
        try {
            action()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            logWarning(logTag, "database action failed", error)
            rollback()
            emitMessage(UiMessage(errorResId))
        }
    }
}
