package com.georgv.audioworkstation.core.session
import com.georgv.audioworkstation.core.audio.FakeAudioController
import com.georgv.audioworkstation.data.db.dao.FakeProjectDao
import com.georgv.audioworkstation.core.audio.NoopProjectFileStore
import com.georgv.audioworkstation.ui.screens.projects.ProjectViewModelMainDispatcherRule

import com.georgv.audioworkstation.core.audio.CapturePort
import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.core.audio.MasterOutputMeterState
import com.georgv.audioworkstation.core.audio.MultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.TrackPlaybackLane
import com.georgv.audioworkstation.core.audio.RecordingSpec
import com.georgv.audioworkstation.core.audio.capability.testSessionTransportCapabilityGate
import com.georgv.audioworkstation.core.audio.testProjectRecordingCoordinator
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectTransportControllerTest {

    @get:Rule
    val mainDispatcherRule = ProjectViewModelMainDispatcherRule()

    private companion object {
        const val PID = "project-transport-test"
    }

    private fun project() = ProjectEntity(id = PID, name = "P")

    private fun track(id: String) =
        TrackEntity(
            id = id,
            projectId = PID,
            name = id,
            position = 0,
            wavFilePath = "$id.wav",
            channelMode = ChannelMode.MONO,
        )

    private fun recordingSessionSharingEngine(
        scope: CoroutineScope,
        audio: FakeAudioController,
    ): RecordingSessionController {
        val repo = ProjectRepository(FakeProjectDao(projects = listOf(project()), tracks = emptyList()), NoopProjectFileStore)
        val coordinator = testProjectRecordingCoordinator(repo, audio)
        return RecordingSessionController(scope, audio, audio, coordinator, testDispatchers(), testSessionTransportCapabilityGate())
    }

    private fun testDispatchers(): TestAppDispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher)

    @Test
    fun `stopAll clears recording markers and stops recorder before playback engine when both active`() =
        runTest(mainDispatcherRule.dispatcher) {
            val journal = mutableListOf<String>()
            val audio = JournalAudioController(journal)
            val recordingSession = recordingSessionSharingEngine(this, audio)
            recordingSession.seedRecordingStateForTests("a", track("a"), startup = true)
            val finalizedIds = mutableListOf<String>()

            val playback = PlaybackSessionController(
                scope = this,
                playback = audio,
                dispatchers = testDispatchers(),
                loadCurrentProject = { if (it == PID) project() else null },
                currentProjectId = { PID },
                visibleTracks = { emptyList() },
                onPlaybackCompleted = {},
                suppressTransportOnPlaybackCompletion = { false },
            )

            assertTrue(
                audio.startPlayback(
                    MultiPlaybackSpec(
                        sampleRate = 48_000,
                        lanes = listOf(TrackPlaybackLane("x", "x.wav", 1f)),
                    ),
                ),
            )
            playback.markPlayingAndStartCompletionMonitor("x")
            advanceUntilIdle()

            val sut = ProjectTransportController(
                capture = audio,
                playbackSession = playback,
                recordingSession = recordingSession,
                dispatchers = testDispatchers(),
                onLiveOverdubSessionEnd = { },
                finalizeRecordingTrackAfterSuccessfulEngineStop = { trackId, _ -> finalizedIds += trackId },
            )

            sut.stopAll()

            assertEquals(false, recordingSession.recordingStartup.value)
            assertNull(recordingSession.recordingTrackId.value)
            assertNull(recordingSession.optimisticRecordingTrack.value)
            assertEquals(emptySet<String>(), playback.sessionTrackIds.value)
            assertEquals(listOf("a"), finalizedIds)
            assertEquals(listOf("stopRecording", "stopPlayback"), audio.engineStopJournal)
        }

    @Test
    fun `stopAll passes first captured sample transport ms to finalize callback`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio =
                FakeAudioController().apply {
                    recordingFirstSampleTransportPositionMsValue = 187L
                }
            val recordingSession = recordingSessionSharingEngine(this, audio)
            recordingSession.seedRecordingStateForTests("a", track("a"), startup = false)
            var capturedFirstSample = Long.MIN_VALUE

            val playback =
                PlaybackSessionController(
                    scope = this,
                    playback = audio,
                    dispatchers = testDispatchers(),
                    loadCurrentProject = { if (it == PID) project() else null },
                    currentProjectId = { PID },
                    visibleTracks = { emptyList() },
                    onPlaybackCompleted = {},
                    suppressTransportOnPlaybackCompletion = { false },
                )

            val sut =
                ProjectTransportController(
                    capture = audio,
                    playbackSession = playback,
                    recordingSession = recordingSession,
                    dispatchers = testDispatchers(),
                    onLiveOverdubSessionEnd = { },
                    finalizeRecordingTrackAfterSuccessfulEngineStop = { _, snapshot ->
                        capturedFirstSample = snapshot.firstSampleTransportPositionMs
                    },
                )

            sut.stopAll()

            assertEquals(187L, capturedFirstSample)
        }

    @Test
    fun `stopAll skips recorder when no recording row and still stops playback`() =
        runTest(mainDispatcherRule.dispatcher) {
            val journal = mutableListOf<String>()
            val audio = JournalAudioController(journal)
            val recordingSession = recordingSessionSharingEngine(this, audio)
            recordingSession.seedRecordingStateForTests(null, null, startup = false)

            val playback = PlaybackSessionController(
                scope = this,
                playback = audio,
                dispatchers = testDispatchers(),
                loadCurrentProject = { if (it == PID) project() else null },
                currentProjectId = { PID },
                visibleTracks = { emptyList() },
                onPlaybackCompleted = {},
                suppressTransportOnPlaybackCompletion = { false },
            )
            assertTrue(
                audio.startPlayback(
                    MultiPlaybackSpec(
                        sampleRate = 48_000,
                        lanes = listOf(TrackPlaybackLane("x", "x.wav", 1f)),
                    ),
                ),
            )
            playback.markPlayingAndStartCompletionMonitor("x")
            advanceUntilIdle()

            val sut = ProjectTransportController(
                capture = audio,
                playbackSession = playback,
                recordingSession = recordingSession,
                dispatchers = testDispatchers(),
                onLiveOverdubSessionEnd = { },
                finalizeRecordingTrackAfterSuccessfulEngineStop = { _, _ -> error("finalize not expected") },
            )

            sut.stopAll()

            assertEquals(listOf("stopPlayback"), audio.engineStopJournal)
        }

    @Test
    fun `resetPlaybackForProjectChange clears playing markers via session`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = JournalAudioController(mutableListOf())
            val recordingSession = recordingSessionSharingEngine(this, audio)
            val playback = PlaybackSessionController(
                scope = this,
                playback = audio,
                dispatchers = testDispatchers(),
                loadCurrentProject = { project() },
                currentProjectId = { PID },
                visibleTracks = { emptyList() },
                onPlaybackCompleted = {},
                suppressTransportOnPlaybackCompletion = { false },
            )
            assertTrue(
                audio.startPlayback(
                    MultiPlaybackSpec(
                        sampleRate = 48_000,
                        lanes = listOf(TrackPlaybackLane("x", "x.wav", 1f)),
                    ),
                ),
            )
            playback.markPlayingAndStartCompletionMonitor("z")
            advanceUntilIdle()

            val sut =
                ProjectTransportController(
                    capture = audio,
                    playbackSession = playback,
                    recordingSession = recordingSession,
                    dispatchers = testDispatchers(),
                    onLiveOverdubSessionEnd = { },
                    finalizeRecordingTrackAfterSuccessfulEngineStop = { _, _ -> },
                )
            sut.resetPlaybackForProjectChange()
            advanceUntilIdle()

            assertEquals(emptySet<String>(), playback.sessionTrackIds.value)
        }

    /** Records [stopRecording]/[stopPlayback] relative order. */
    private class JournalAudioController(val journal: MutableList<String>) : FakeAudioController() {
        val engineStopJournal: List<String>
            get() =
                journal.filter { it.startsWith("stopRecording") || it.startsWith("stopPlayback") }.map {
                    when {
                        it.startsWith("stopRecording") -> "stopRecording"
                        else -> "stopPlayback"
                    }
                }

        override fun stopRecording(): Boolean {
            journal += "stopRecording"
            return super.stopRecording()
        }

        override fun startPlayback(spec: MultiPlaybackSpec): Boolean {
            journal += "startPlayback"
            return super.startPlayback(spec)
        }

        override fun stopPlayback(): Boolean {
            journal += "stopPlayback"
            return super.stopPlayback()
        }
    }
}
