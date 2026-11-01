package com.georgv.audioworkstation.ui.screens.library

import com.georgv.audioworkstation.core.audio.FakeAudioController
import com.georgv.audioworkstation.core.audio.ProjectSampleRate
import com.georgv.audioworkstation.core.audio.capability.CapabilityProfileState
import com.georgv.audioworkstation.core.audio.capability.CapabilityValidationFlags
import com.georgv.audioworkstation.core.audio.capability.ResolvedAudioCapability
import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMixPreviewPlayerTest {
    @Test
    fun `play arms the project tracks on the timeline`() = runTest {
        val audio = FakeAudioController()
        val player = libraryPreviewPlayer(audio)
        val project = ProjectEntity(id = "project-a", sampleRate = ProjectSampleRate.RATE_44_100.hz)

        assertTrue(player.play(project, showcaseTracks()))

        val spec = audio.lastMultiPlaybackSpec
        assertEquals(1, audio.startPlaybackCalls)
        assertEquals(ProjectSampleRate.RATE_44_100.hz, spec?.sampleRate)
        assertEquals("/mix/song.wav", spec?.lanes?.get(0)?.wavFilePath)
        assertEquals(0L, spec?.lanes?.get(0)?.timelineClipStartMs)
        assertEquals("/mix/take.wav", spec?.lanes?.get(1)?.wavFilePath)
        assertEquals(196L, spec?.lanes?.get(1)?.timelineClipStartMs)
        assertEquals(0L, spec?.startPositionMs)
        assertEquals(213_394L, spec?.sessionTimelineEndMs)
        assertEquals(213_394, player.transportState.value.durationMs)
        assertTrue(player.transportState.value.playing)
    }

    @Test
    fun `scrub restarts the tracks from the released playhead`() = runTest {
        val audio = FakeAudioController()
        val player = libraryPreviewPlayer(audio)
        val project = ProjectEntity(id = "project-a", sampleRate = ProjectSampleRate.RATE_44_100.hz)
        player.play(project, showcaseTracks())

        player.holdForScrub()
        assertTrue(player.isPlaying("project-a"))

        player.seekTo(400)
        assertEquals(2, audio.startPlaybackCalls)
        assertEquals(400L, audio.lastMultiPlaybackSpec?.startPositionMs)
        assertEquals(196L, audio.lastMultiPlaybackSpec?.lanes?.get(1)?.timelineClipStartMs)
        assertEquals(400, player.transportState.value.positionMs)
        assertTrue(player.transportState.value.playing)
    }

    @Test
    fun `clock follows the engine transport and ends when the engine stops`() = runTest {
        val audio = FakeAudioController()
        val player = libraryPreviewPlayer(audio)
        val project = ProjectEntity(id = "project-a", sampleRate = ProjectSampleRate.RATE_44_100.hz)
        player.play(project, showcaseTracks())

        audio.transportPositionMsValue = 250L
        player.tick()
        assertEquals(250, player.transportState.value.positionMs)

        audio.completePlayback()
        player.tick()
        assertFalse(player.isPlaying("project-a"))
        assertEquals(213_394, player.transportState.value.positionMs)
    }

    @Test
    fun `project without playable tracks does not start the engine`() = runTest {
        val audio = FakeAudioController()
        val player = libraryPreviewPlayer(audio)
        val project = ProjectEntity(id = "project-a")

        assertFalse(player.play(project, emptyList()))
        assertEquals(0, audio.startPlaybackCalls)
    }
}

private fun showcaseTracks(): List<TrackEntity> =
    listOf(
        TrackEntity(
            id = "song",
            projectId = "project-a",
            wavFilePath = "/mix/song.wav",
            duration = 213_394L,
            position = 0,
        ),
        TrackEntity(
            id = "take",
            projectId = "project-a",
            wavFilePath = "/mix/take.wav",
            duration = 14_994L,
            timelineStartOffsetMs = 216L,
            overdubPlaybackSyncOffsetMs = 20L,
            position = 1,
        ),
    )

internal fun libraryPreviewPlayer(
    audio: FakeAudioController = FakeAudioController(),
    dispatchers: AppDispatchers = TestAppDispatchers(),
): LibraryMixPreviewPlayer =
    LibraryMixPreviewPlayer(
        playback = audio,
        meter = audio,
        sessionTransportGate = ReadyTransportGate(),
        dispatchers = dispatchers,
    )

private class ReadyTransportGate : SessionTransportCapabilityGate {
    private var prepared: ResolvedAudioCapability? = null

    override suspend fun prepareForLiveSession(sampleRate: Int): ResolvedAudioCapability {
        val resolved = ResolvedAudioCapability(
            profile = null,
            profileId = "test",
            routeKey = "test",
            sampleRate = sampleRate,
            outputLatencyMs = null,
            inputCaptureDelayMs = null,
            inputHalLatencyMs = null,
            roundTripMs = null,
            jitterMs = null,
            confidence = 1.0,
            lowLatencyOutputPathGranted = true,
            lowLatencyInputPathGranted = true,
            profileState = CapabilityProfileState.VALIDATED,
            routeUnchanged = true,
            validation = CapabilityValidationFlags(),
            warnings = emptyList(),
            dataComplete = true,
        )
        prepared = resolved
        return resolved
    }

    override fun ensurePreparedForSampleRate(sampleRate: Int) = Unit

    override fun lastPreparedCapability(): ResolvedAudioCapability? = prepared
}
