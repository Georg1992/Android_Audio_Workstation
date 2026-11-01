package com.georgv.audioworkstation.ui.screens.library

import com.georgv.audioworkstation.core.audio.FakeAudioController
import com.georgv.audioworkstation.core.audio.ProjectSampleRate
import com.georgv.audioworkstation.core.audio.capability.CapabilityProfileState
import com.georgv.audioworkstation.core.audio.capability.CapabilityValidationFlags
import com.georgv.audioworkstation.core.audio.capability.ResolvedAudioCapability
import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import com.georgv.audioworkstation.core.audio.writeConstantPcm16Wav
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMixPreviewPlayerTest {
    @Test
    fun `play arms one mix lane on the project engine`() = runTest {
        val audio = FakeAudioController()
        val player = libraryPreviewPlayer(audio)
        val file = stereoMix(frames = ProjectSampleRate.RATE_44_100.hz)

        assertTrue(player.play("project-a", file.absolutePath))

        val spec = audio.lastMultiPlaybackSpec
        assertEquals(1, audio.startPlaybackCalls)
        assertEquals(ProjectSampleRate.RATE_44_100.hz, spec?.sampleRate)
        assertEquals(file.absolutePath, spec?.lanes?.single()?.wavFilePath)
        assertEquals(0L, spec?.startPositionMs)
        assertEquals(1_000L, spec?.sessionTimelineEndMs)
        assertEquals("project-a", player.transportState.value.projectId)
        assertTrue(player.transportState.value.playing)
        assertEquals(1_000, player.transportState.value.durationMs)
    }

    @Test
    fun `scrub pauses the engine and restarts from the released playhead`() = runTest {
        val audio = FakeAudioController()
        val player = libraryPreviewPlayer(audio)
        val file = stereoMix(frames = ProjectSampleRate.RATE_44_100.hz)
        player.play("project-a", file.absolutePath)

        player.holdForScrub()
        assertEquals(2, audio.stopPlaybackCalls)
        assertTrue(player.isPlaying("project-a"))

        player.seekTo(400)
        assertEquals(2, audio.startPlaybackCalls)
        assertEquals(400L, audio.lastMultiPlaybackSpec?.startPositionMs)
        assertTrue(player.transportState.value.playing)
        assertEquals(400, player.transportState.value.positionMs)
    }

    @Test
    fun `clock follows the engine transport and ends when the engine stops`() = runTest {
        val audio = FakeAudioController()
        val player = libraryPreviewPlayer(audio)
        val file = stereoMix(frames = ProjectSampleRate.RATE_44_100.hz)
        player.play("project-a", file.absolutePath)

        audio.transportPositionMsValue = 250L
        player.tick()
        assertEquals(250, player.transportState.value.positionMs)

        audio.completePlayback()
        player.tick()
        assertFalse(player.isPlaying("project-a"))
        assertEquals(1_000, player.transportState.value.positionMs)
    }

    @Test
    fun `unsupported mix rate does not start the engine`() = runTest {
        val audio = FakeAudioController()
        val player = libraryPreviewPlayer(audio)
        val file = File.createTempFile("mix-rate", ".wav")
        writeConstantPcm16Wav(file, sampleValue = 0, frameCount = 100, channelCount = 2, sampleRateHz = 1_000)

        assertFalse(player.play("project-a", file.absolutePath))
        assertEquals(0, audio.startPlaybackCalls)
    }
}

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

private fun stereoMix(frames: Int): File {
    val file = File.createTempFile("mix-preview", ".wav")
    writeConstantPcm16Wav(
        file = file,
        sampleValue = 1_000,
        frameCount = frames,
        channelCount = 2,
        sampleRateHz = frames,
    )
    return file
}
