package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.core.audio.RecordingStorageFsQuery
import com.georgv.audioworkstation.core.audio.RecordingStorageGuard
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingMonitorTest {

    @Test
    fun `storage stop runs after the monitor cancels its own job`() = runTest {
        val dispatchers = dispatchers()
        var available = RecordingStorageGuard.DEFAULT_RESERVE_BYTES + 1L
        val guard =
            RecordingStorageGuard(
                object : RecordingStorageFsQuery {
                    override fun availableBytes(path: String): Long? = available
                },
            )
        val monitor =
            RecordingStorageMonitor(
                scope = this,
                guard = guard,
                dispatchers = dispatchers,
                pollIntervalMs = 1_000L,
            )
        var recording = true
        var finished = false
        monitor.start(
            projectDirectoryPath = "/proj",
            isRecordingActive = { recording },
        ) {
            monitor.stop()
            delay(1)
            finished = true
            recording = false
        }
        available = 0L
        advanceUntilIdle()
        assertTrue(finished)
        assertFalse(recording)
    }

    @Test
    fun `capture failure ends recording after the monitor job is cancelled`() = runTest {
        val monitor =
            RecordingCaptureFailureMonitor(
                scope = this,
                dispatchers = dispatchers(),
                pollIntervalMs = 200L,
            )
        var recording = true
        var captureFailed = false
        var stopped = false
        monitor.start(
            isRecordingActive = { recording },
            captureFailed = { captureFailed },
        ) {
            monitor.stop()
            delay(1)
            stopped = true
            recording = false
        }
        captureFailed = true
        advanceUntilIdle()
        assertTrue(stopped)
        assertFalse(recording)
    }

    private fun TestScope.dispatchers(): TestAppDispatchers {
        val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        return TestAppDispatchers.unified(dispatcher)
    }
}
