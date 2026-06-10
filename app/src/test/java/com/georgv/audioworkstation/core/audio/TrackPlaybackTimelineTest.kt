package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackPlaybackTimelineTest {
    @Test
    fun `plain record playback clip start matches visual offset`() {
        val track =
            TrackEntity(
                id = "a",
                projectId = "p",
                timelineStartOffsetMs = 187L,
            )
        assertEquals(187L, track.playbackTimelineClipStartMs())
    }

    @Test
    fun `overdub playback clip start subtracts sync offset from capture placement`() {
        val track =
            TrackEntity(
                id = "b",
                projectId = "p",
                timelineStartOffsetMs = 541L,
                overdubPlaybackSyncOffsetMs = 62L,
                overdubBackingArmMs = 0L,
            )
        assertEquals(479L, track.playbackTimelineClipStartMs())
    }
}
