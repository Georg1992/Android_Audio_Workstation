package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * While a take is active, notices that native capture ended on an input or disk error.
 * Invokes [onCaptureFailed] at most once per start cycle, on [scope] so cancelling this
 * monitor does not cancel the stop that follows.
 */
class RecordingCaptureFailureMonitor(
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {
    private var monitorJob: Job? = null
    private val failureStopInFlight = AtomicBoolean(false)

    fun start(
        isRecordingActive: () -> Boolean,
        captureFailed: () -> Boolean,
        onCaptureFailed: suspend () -> Unit,
    ) {
        stop()
        failureStopInFlight.set(false)
        monitorJob =
            scope.launch(dispatchers.io) {
                while (isActive && isRecordingActive()) {
                    delay(pollIntervalMs)
                    if (!isRecordingActive()) break
                    if (captureFailed()) {
                        if (failureStopInFlight.compareAndSet(false, true)) {
                            scope.launch { onCaptureFailed() }
                        }
                        break
                    }
                }
            }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    companion object {
        const val POLL_INTERVAL_MS = 200L
    }
}
