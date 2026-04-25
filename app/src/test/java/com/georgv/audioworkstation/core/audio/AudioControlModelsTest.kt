package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
