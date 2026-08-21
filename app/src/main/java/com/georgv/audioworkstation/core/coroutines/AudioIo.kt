package com.georgv.audioworkstation.core.coroutines

import com.georgv.audioworkstation.core.diagnostics.ThreadingDiagnostics
import kotlinx.coroutines.withContext

suspend fun <T> withAudioIo(
    dispatchers: AppDispatchers,
    operation: String,
    block: suspend () -> T,
): T {
    ThreadingDiagnostics.logCallerBoundary(operation, phase = "before")
    val startMs = System.currentTimeMillis()
    val result =
        withContext(dispatchers.audioIo) {
            ThreadingDiagnostics.logOperationStart(operation)
            try {
                block()
            } finally {
                ThreadingDiagnostics.logOperationEnd(operation, System.currentTimeMillis() - startMs)
            }
        }
    ThreadingDiagnostics.logCallerBoundary(operation, phase = "after")
    return result
}

suspend fun <T> withIo(
    dispatchers: AppDispatchers,
    operation: String,
    block: suspend () -> T,
): T {
    ThreadingDiagnostics.logCallerBoundary(operation, phase = "before")
    val startMs = System.currentTimeMillis()
    val result =
        withContext(dispatchers.io) {
            ThreadingDiagnostics.logOperationStart(operation)
            try {
                block()
            } finally {
                ThreadingDiagnostics.logOperationEnd(operation, System.currentTimeMillis() - startMs)
            }
        }
    ThreadingDiagnostics.logCallerBoundary(operation, phase = "after")
    return result
}
