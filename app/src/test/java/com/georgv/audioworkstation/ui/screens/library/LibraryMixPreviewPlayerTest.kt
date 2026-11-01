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
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMixPreviewPlayerTest {
    @Test
    fun `play arms the finished mix file`() = runTest {
        val audio = FakeAudioController()
        val player = libraryPreviewPlayer(audio)
        val mix = mixFile()

        assertTrue(player.play("project-a", mix.absolutePath))

        val spec = audio.lastMultiPlaybackSpec
        assertEquals(1, audio.startPlaybackCalls)
        assertEquals(ProjectSampleRate.RATE_44_100.hz, spec?.sampleRate)
        assertEquals(1, spec?.lanes?.size)
        assertEquals(mix.absolutePath, spec?.lanes?.single()?.wavFilePath)
        assertEquals(0L, spec?.lanes?.single()?.timelineClipStartMs)
        assertEquals(0L, spec?.startPositionMs)
        assertEquals(player.transportState.value.durationMs.toLong(), spec?.sessionTimelineEndMs)
        assertTrue(player.transportState.value.playing)
    }

    @Test
    fun `scrub restarts the mix from the released playhead`() = runTest {
        val audio = FakeAudioController()
        val player = libraryPreviewPlayer(audio)
        val mix = mixFile()
        player.play("project-a", mix.absolutePath)

        player.holdForScrub()
        assertTrue(player.isPlaying("project-a"))

        player.seekTo(40)
        assertEquals(2, audio.startPlaybackCalls)
        assertEquals(mix.absolutePath, audio.lastMultiPlaybackSpec?.lanes?.single()?.wavFilePath)
        assertEquals(40L, audio.lastMultiPlaybackSpec?.startPositionMs)
        assertEquals(40, player.transportState.value.positionMs)
        assertTrue(player.transportState.value.playing)
    }

    @Test
    fun `clock follows the engine transport and ends when the engine stops`() = runTest {
        val audio = FakeAudioController()
        val player = libraryPreviewPlayer(audio)
        player.play("project-a", mixFile().absolutePath)
        val durationMs = player.transportState.value.durationMs

        audio.transportPositionMsValue = 25L
        player.tick()
        assertEquals(25, player.transportState.value.positionMs)

        audio.completePlayback()
        player.tick()
        assertFalse(player.isPlaying("project-a"))
        assertEquals(durationMs, player.transportState.value.positionMs)
    }

    @Test
    fun `missing mix file does not start the engine`() = runTest {
        val audio = FakeAudioController()
        val player = libraryPreviewPlayer(audio)

        assertFalse(player.play("project-a", File("missing-mix.wav").absolutePath))
        assertEquals(0, audio.startPlaybackCalls)
    }
}

private fun mixFile(): File {
    val wav = Files.createTempDirectory("library-mix").toFile().resolve("mixdown.wav")
    writeConstantPcm16Wav(
        file = wav,
        sampleValue = 1_000,
        frameCount = 4_410,
        channelCount = 2,
        sampleRateHz = ProjectSampleRate.RATE_44_100.hz,
    )
    return wav
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
