package com.georgv.audioworkstation.ui.screens.projects

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectImportSessionTest {

    @Test
    fun cancelAllJobsAndClear_cancelsActiveJobsAndEmptiesMap() = runTest {
        val session = ProjectImportSession()
        var cancelled = false
        val gate = CompletableDeferred<Unit>()
        val job: Job =
            backgroundScope.launch {
                try {
                    gate.await()
                } finally {
                    cancelled = true
                }
            }
        session.jobs["track-1"] = job
        runCurrent()
        assertTrue(job.isActive)

        session.cancelAllJobsAndClear()
        runCurrent()

        assertTrue(session.jobs.isEmpty())
        assertFalse(job.isActive)
        assertTrue(cancelled)
    }
}
