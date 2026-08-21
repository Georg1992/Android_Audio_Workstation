package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.core.audio.RecordingStorageGuard
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls free space while a take is active. Invokes [onStorageExhausted] at most once per start cycle.
 */
class RecordingStorageMonitor(
    private val scope: CoroutineScope,
    private val guard: RecordingStorageGuard,
    private val dispatchers: AppDispatchers,
    private val pollIntervalMs: Long = RecordingStorageGuard.MONITOR_POLL_INTERVAL_MS,
) {
    private var monitorJob: Job? = null
    private val storageStopInFlight = AtomicBoolean(false)

    fun start(
        projectDirectoryPath: String,
        isRecordingActive: () -> Boolean,
        onStorageExhausted: suspend () -> Unit,
    ) {
        stop()
        storageStopInFlight.set(false)
        monitorJob =
            scope.launch(dispatchers.io) {
                while (isActive && isRecordingActive()) {
                    delay(pollIntervalMs)
                    if (!isRecordingActive()) break
                    val available = guard.availableBytes(projectDirectoryPath)
                    if (available == null || !guard.hasReserveRemaining(available)) {
                        if (storageStopInFlight.compareAndSet(false, true)) {
                            onStorageExhausted()
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
}
