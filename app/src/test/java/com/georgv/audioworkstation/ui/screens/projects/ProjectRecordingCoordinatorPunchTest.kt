package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.RecordingPunchContext
import com.georgv.audioworkstation.core.audio.TempDirAudioFilePathProvider
import com.georgv.audioworkstation.core.audio.WavPunchSplicer
import com.georgv.audioworkstation.core.audio.testProjectRecordingCoordinator
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import com.georgv.audioworkstation.core.audio.writeConstantPcm16Wav
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectRecordingCoordinatorPunchTest {

    @get:Rule
    val mainDispatcherRule = ProjectViewModelMainDispatcherRule()

    private fun project() = ProjectEntity(id = "p", name = "P")

    @Test
    fun `prepareExistingTrackForRecording keeps clip-local splice start separate from transport start`() {
        val coordinator =
            ProjectRecordingCoordinator(
                repo = ProjectRepository(FakeProjectDao(), NoopProjectFileStore),
                audioController = FakeAudioController(),
                audioFilePathProvider = TempDirAudioFilePathProvider(),
                wavPunchSplicer = WavPunchSplicer(),
                dispatchers = TestAppDispatchers(),
            )
        val track =
            TrackEntity(
                id = "track",
                projectId = "p",
                position = 0,
                wavFilePath = "track.wav",
                duration = 20_000L,
                timelineStartOffsetMs = 3_000L,
            )

        val prepared =
            coordinator.prepareExistingTrackForRecording(
                track = track,
                playheadPositionMs = 5_000L,
            )

        assertEquals(2_000L, prepared.spliceStartInClipMs)
        assertEquals(5_000L, prepared.recordingTransportStartMs)
        assertEquals(3_000L, prepared.track.timelineStartOffsetMs)
    }

    @Test
    fun `beginRecording into existing track seeds native transport at global playhead`() =
        runTest(mainDispatcherRule.dispatcher) {
        val dir = File.createTempFile("coord-punch-transport", "").apply { delete(); mkdirs() }
        val existing =
            TrackEntity(
                id = "track",
                projectId = "p",
                position = 0,
                wavFilePath = File(dir, "track.wav").absolutePath,
                duration = 20_000L,
                timelineStartOffsetMs = 0L,
            )
        val audio = FakeAudioController(startRecordingPath = File(dir, "rec.wav").absolutePath)
        val dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher)
        val coordinator =
            testProjectRecordingCoordinator(
                repo = ProjectRepository(FakeProjectDao(), NoopProjectFileStore),
                audio = audio,
                paths = TempDirAudioFilePathProvider(dir),
                dispatchers = dispatchers,
            )

        coordinator.beginRecording(
            projectId = "p",
            project = project(),
            visibleTrackCount = 1,
            timelineStartOffsetMs = 5_000L,
            recordTargetTrack = existing,
            playheadPositionMs = 5_000L,
        )

        assertEquals(5_000L, audio.lastRecordingSpec?.timelineStartOffsetMs)
        assertEquals(0L, existing.timelineStartOffsetMs)
    }

    @Test
    fun `beginRecording without record target still seeds native transport from new track offset`() =
        runTest(mainDispatcherRule.dispatcher) {
        val dir = File.createTempFile("coord-new-track-transport", "").apply { delete(); mkdirs() }
        val audio = FakeAudioController(startRecordingPath = File(dir, "rec.wav").absolutePath)
        val dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher)
        val coordinator =
            testProjectRecordingCoordinator(
                repo = ProjectRepository(FakeProjectDao(), NoopProjectFileStore),
                audio = audio,
                paths = TempDirAudioFilePathProvider(dir),
                dispatchers = dispatchers,
            )

        coordinator.beginRecording(
            projectId = "p",
            project = project(),
            visibleTrackCount = 0,
            timelineStartOffsetMs = 5_000L,
            recordTargetTrack = null,
            playheadPositionMs = 5_000L,
        )

        assertEquals(5_000L, audio.lastRecordingSpec?.timelineStartOffsetMs)
    }

    @Test
    fun `finalizeTrackAfterStop splices temp recording into existing track wav`() {
        val dir = File.createTempFile("coord-punch", "").apply { delete(); mkdirs() }
        val original = File(dir, "track.wav")
        val tempRecording = File(dir, "track.recording.tmp.wav")
        writeConstantPcm16Wav(original, sampleValue = 1_000, frameCount = 20_000)
        writeConstantPcm16Wav(tempRecording, sampleValue = 2_000, frameCount = 3_000)

        val repo = ProjectRepository(FakeProjectDao(), NoopProjectFileStore)
        val coordinator =
            ProjectRecordingCoordinator(
                repo = repo,
                audioController = FakeAudioController(),
                audioFilePathProvider = TempDirAudioFilePathProvider(dir),
                wavPunchSplicer = WavPunchSplicer(),
                dispatchers = TestAppDispatchers(),
            )

        val track =
            TrackEntity(
                id = "track",
                projectId = "p",
                position = 0,
                wavFilePath = original.absolutePath,
                duration = 20_000L,
                timeStampStart = 1L,
                isRecording = true,
            )
        val finalized =
            coordinator.finalizeTrackAfterStop(
                currentTrack = track,
                punchContext =
                    RecordingPunchContext(
                        originalWavPath = original.absolutePath,
                        tempRecordingPath = tempRecording.absolutePath,
                        finalWavPath = original.absolutePath,
                        spliceStartInClipMs = 5_000L,
                        sampleRateHz = 1_000,
                        fileBitDepth = 16,
                    ),
            )

        assertEquals(20_000L, finalized.duration)
        assertEquals(false, finalized.isRecording)
        assertTrue(original.exists())
        assertEquals(false, tempRecording.exists())
    }

    @Test
    fun `finalizeTrackAfterStop updates duration when punch extends wav`() {
        val dir = File.createTempFile("coord-punch-extend", "").apply { delete(); mkdirs() }
        val original = File(dir, "track.wav")
        val tempRecording = File(dir, "track.recording.tmp.wav")
        writeConstantPcm16Wav(original, sampleValue = 1_000, frameCount = 20_000)
        writeConstantPcm16Wav(tempRecording, sampleValue = 2_000, frameCount = 5_000)

        val repo = ProjectRepository(FakeProjectDao(), NoopProjectFileStore)
        val coordinator =
            ProjectRecordingCoordinator(
                repo = repo,
                audioController = FakeAudioController(),
                audioFilePathProvider = TempDirAudioFilePathProvider(dir),
                wavPunchSplicer = WavPunchSplicer(),
                dispatchers = TestAppDispatchers(),
            )

        val track =
            TrackEntity(
                id = "track",
                projectId = "p",
                position = 0,
                wavFilePath = original.absolutePath,
                duration = 20_000L,
                timeStampStart = 1L,
                isRecording = true,
            )
        val finalized =
            coordinator.finalizeTrackAfterStop(
                currentTrack = track,
                punchContext =
                    RecordingPunchContext(
                        originalWavPath = original.absolutePath,
                        tempRecordingPath = tempRecording.absolutePath,
                        finalWavPath = original.absolutePath,
                        spliceStartInClipMs = 18_000L,
                        sampleRateHz = 1_000,
                        fileBitDepth = 16,
                    ),
            )

        assertEquals(23_000L, finalized.duration)
    }

    @Test
    fun `finalizeTrackAfterStop places new take at first captured sample transport ms`() {
        val coordinator =
            ProjectRecordingCoordinator(
                repo = ProjectRepository(FakeProjectDao(), NoopProjectFileStore),
                audioController = FakeAudioController(),
                audioFilePathProvider = TempDirAudioFilePathProvider(),
                wavPunchSplicer = WavPunchSplicer(),
                dispatchers = TestAppDispatchers(),
            )
        val track =
            TrackEntity(
                id = "take",
                projectId = "p",
                position = 1,
                wavFilePath = "take.wav",
                timelineStartOffsetMs = 0L,
                timeStampStart = 1L,
                isRecording = true,
            )

        val finalized =
            coordinator.finalizeTrackAfterStop(
                currentTrack = track,
                punchContext = null,
                firstSampleTransportPositionMs = 187L,
            )

        assertEquals(187L, finalized.timelineStartOffsetMs)
    }

    @Test
    fun `finalizeTrackAfterStop punch keeps existing clip timeline offset`() {
        val dir = File.createTempFile("coord-punch-offset", "").apply { delete(); mkdirs() }
        val original = File(dir, "track.wav")
        val tempRecording = File(dir, "track.recording.tmp.wav")
        writeConstantPcm16Wav(original, sampleValue = 1_000, frameCount = 1_000)
        writeConstantPcm16Wav(tempRecording, sampleValue = 2_000, frameCount = 500)

        val coordinator =
            ProjectRecordingCoordinator(
                repo = ProjectRepository(FakeProjectDao(), NoopProjectFileStore),
                audioController = FakeAudioController(),
                audioFilePathProvider = TempDirAudioFilePathProvider(dir),
                wavPunchSplicer = WavPunchSplicer(),
                dispatchers = TestAppDispatchers(),
            )
        val track =
            TrackEntity(
                id = "track",
                projectId = "p",
                position = 0,
                wavFilePath = original.absolutePath,
                duration = 20_000L,
                timelineStartOffsetMs = 5_000L,
                timeStampStart = 1L,
                isRecording = true,
            )

        val finalized =
            coordinator.finalizeTrackAfterStop(
                currentTrack = track,
                punchContext =
                    RecordingPunchContext(
                        originalWavPath = original.absolutePath,
                        tempRecordingPath = tempRecording.absolutePath,
                        finalWavPath = original.absolutePath,
                        spliceStartInClipMs = 1_000L,
                        sampleRateHz = 1_000,
                        fileBitDepth = 16,
                    ),
                firstSampleTransportPositionMs = 5_200L,
            )

        assertEquals(5_000L, finalized.timelineStartOffsetMs)
    }
}
