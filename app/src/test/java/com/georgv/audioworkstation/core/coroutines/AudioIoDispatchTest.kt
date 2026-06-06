package com.georgv.audioworkstation.core.coroutines

import com.georgv.audioworkstation.core.audio.testProjectRecordingCoordinator
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.screens.projects.FakeAudioController
import com.georgv.audioworkstation.ui.screens.projects.FakeProjectDao
import com.georgv.audioworkstation.ui.screens.projects.NoopProjectFileStore
import com.georgv.audioworkstation.ui.screens.projects.RecordingStartOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

class AudioIoDispatchTest {

    @Test
    fun `withAudioIo runs block on audioIo dispatcher not caller thread`() = runBlocking {
        val audioIoExecutor = Executors.newSingleThreadExecutor()
        val audioIoDispatcher = audioIoExecutor.asCoroutineDispatcher()
        val dispatchers =
            TestAppDispatchers(
                main = Dispatchers.Default,
                io = Dispatchers.Default,
                default = Dispatchers.Default,
                audioIo = audioIoDispatcher,
            )
        val callerThread = Thread.currentThread()
        val workThread = CompletableDeferred<Thread>()
        try {
            withAudioIo(dispatchers, "test-op") {
                workThread.complete(Thread.currentThread())
            }
            assertNotEquals(callerThread, workThread.await())
        } finally {
            audioIoDispatcher.close()
            audioIoExecutor.shutdownNow()
        }
    }

    @Test
    fun `startEngineForAllocatedTrack invokes startRecording on audioIo dispatcher`() = runBlocking {
        val audioIoExecutor = Executors.newSingleThreadExecutor()
        val audioIoDispatcher = audioIoExecutor.asCoroutineDispatcher()
        val dispatchers =
            TestAppDispatchers(
                main = Dispatchers.Default,
                io = Dispatchers.Default,
                default = Dispatchers.Default,
                audioIo = audioIoDispatcher,
            )
        val callerThread = Thread.currentThread()
        val recordingThread = CompletableDeferred<Thread>()
        val dir = File.createTempFile("audio-io-rec", "").apply { delete(); mkdirs() }
        val audio = FakeAudioController(startRecordingPath = File(dir, "rec.wav").absolutePath)
        audio.onEnterStartRecording = {
            recordingThread.complete(Thread.currentThread())
        }
        val coordinator =
            testProjectRecordingCoordinator(
                repo = ProjectRepository(FakeProjectDao(), NoopProjectFileStore),
                audio = audio,
                dispatchers = dispatchers,
            )
        try {
            val pending =
                coordinator.allocatePendingRecordingTrack(
                    projectId = "p",
                    visibleTrackCount = 0,
                )
            val outcome =
                coordinator.startEngineForAllocatedTrack(
                    project = ProjectEntity(id = "p", name = "P"),
                    pendingTrack = pending,
                )
            assertTrue(outcome is RecordingStartOutcome.ReadyToPersistRecordingRow)
            assertNotEquals(callerThread, recordingThread.await())
        } finally {
            audioIoDispatcher.close()
            audioIoExecutor.shutdownNow()
        }
    }
}
