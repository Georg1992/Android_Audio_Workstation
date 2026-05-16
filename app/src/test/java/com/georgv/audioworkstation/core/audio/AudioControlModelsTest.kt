package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AudioControlModelsTest {

    @Test
    fun `toRecordingSpec keeps only recording fields the engine currently uses`() {
        val project = ProjectEntity(id = "project-1", sampleRate = 48_000, fileBitDepth = 24)
        val track = TrackEntity(
            id = "track-1",
            projectId = "project-1",
            channelMode = ChannelMode.STEREO
        )

        val spec = project.toRecordingSpec(track)

        assertEquals("project-1", spec.projectId)
        assertEquals("track-1", spec.trackId)
        assertEquals(48_000, spec.sampleRate)
        assertEquals(24, spec.fileBitDepth)
        assertEquals(ChannelMode.STEREO, spec.channelMode)
    }

    @Test
    fun `toRecordingRequest adds output path for native recording`() {
        val request = RecordingSpec(
            projectId = "project-1",
            trackId = "track-1",
            sampleRate = 48_000,
            fileBitDepth = 24,
            channelMode = ChannelMode.STEREO
        ).toRecordingRequest("/tmp/track-1.wav")

        assertEquals(48_000, request.sampleRate)
        assertEquals(24, request.fileBitDepth)
        assertEquals(ChannelMode.STEREO, request.channelMode)
        assertEquals("/tmp/track-1.wav", request.outputPath)
    }

    @Test
    fun `toPlaybackSpec maps a single recorded track into playback data`() {
        val project = ProjectEntity(id = "project-1", sampleRate = 48_000)
        val track = TrackEntity(
            id = "b",
            projectId = "project-1",
            wavFilePath = "/tmp/b.wav",
            gain = 75f
        )

        val spec = project.toPlaybackSpec(track)

        assertEquals(48_000, spec?.sampleRate)
        assertEquals("/tmp/b.wav", spec?.wavFilePath)
        assertEquals(0.75f, spec?.gain)
    }

    @Test
    fun `toPlaybackSpec returns null when track has no audio`() {
        val project = ProjectEntity(id = "project-1")
        val track = TrackEntity(id = "a", projectId = "project-1", wavFilePath = "")

        val spec = project.toPlaybackSpec(track)

        assertNull(spec)
    }

    @Test
    fun `toMultiPlaybackSpec maps recorded tracks into normalized lanes`() {
        val project = ProjectEntity(id = "project-1", sampleRate = 44_100)
        val tracks = listOf(
            TrackEntity(id = "a", projectId = "project-1", wavFilePath = "/tmp/a.wav", gain = 25f),
            TrackEntity(id = "b", projectId = "project-1", wavFilePath = "/tmp/b.wav", gain = 150f),
            TrackEntity(id = "c", projectId = "project-1", wavFilePath = "", gain = 50f)
        )

        val spec = project.toMultiPlaybackSpec(tracks)

        assertEquals(44_100, spec?.sampleRate)
        assertEquals(2, spec?.lanes?.size)
        assertEquals(TrackPlaybackLane("a", "/tmp/a.wav", 0.25f), spec?.lanes?.get(0))
        assertEquals(TrackPlaybackLane("b", "/tmp/b.wav", 1f), spec?.lanes?.get(1))
    }

    @Test
    fun `toMultiPlaybackSpec returns null when no playable lanes remain`() {
        val project = ProjectEntity(id = "project-1")
        val tracks = listOf(TrackEntity(id = "a", projectId = "project-1", wavFilePath = ""))

        assertNull(project.toMultiPlaybackSpec(tracks))
    }

    @Test
    fun `toMultiPlaybackSpec returns null above eight playable lanes`() {
        val project = ProjectEntity(id = "project-1")
        val tracks = (1..9).map { index ->
            TrackEntity(
                id = "track-$index",
                projectId = "project-1",
                wavFilePath = "/tmp/track-$index.wav"
            )
        }

        assertNull(project.toMultiPlaybackSpec(tracks))
    }

    @Test
    fun `MultiPlaybackSpec validates supported sample rate`() {
        expectIllegalArgument {
            MultiPlaybackSpec(
                sampleRate = 96_000,
                lanes = listOf(TrackPlaybackLane("a", "/tmp/a.wav", 1f))
            )
        }
    }

    @Test
    fun `TrackPlaybackLane validates path and normalized gain`() {
        expectIllegalArgument { TrackPlaybackLane("a", "", 1f) }
        expectIllegalArgument { TrackPlaybackLane("a", "/tmp/a.wav", 1.1f) }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
