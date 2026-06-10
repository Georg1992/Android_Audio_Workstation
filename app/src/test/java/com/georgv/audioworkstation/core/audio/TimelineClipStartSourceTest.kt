package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineClipStartSourceTest {
    @Test
    fun `scheduling session end matches visual end for plain tracks`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                    timelineStartOffsetMs = 2_000L,
                ),
                TrackEntity(
                    id = "b",
                    projectId = "p",
                    wavFilePath = "b.wav",
                    duration = 5_000L,
                    timelineStartOffsetMs = 1_000L,
                ),
            )
        assertEquals(12_000L, sessionPlaybackSchedulingEndMsForPlayback(tracks))
    }

    @Test
    fun `scheduling session end uses playback clip start not capture placement for overdub`() {
        val track =
            TrackEntity(
                id = "overdub",
                projectId = "p",
                wavFilePath = "overdub.wav",
                duration = 5_000L,
                timelineStartOffsetMs = 144L,
                overdubPlaybackSyncOffsetMs = 62L,
                overdubBackingArmMs = 0L,
            )
        assertEquals(5_082L, sessionPlaybackSchedulingEndMsForPlayback(listOf(track)))
    }

    @Test
    fun `visual spec uses timeline offset while live spec uses scheduling start`() {
        val project = com.georgv.audioworkstation.data.db.entities.ProjectEntity(id = "p", sampleRate = 48_000)
        val track =
            TrackEntity(
                id = "overdub",
                projectId = "p",
                wavFilePath = "overdub.wav",
                duration = 5_000L,
                timelineStartOffsetMs = 144L,
                overdubPlaybackSyncOffsetMs = 62L,
                overdubBackingArmMs = 0L,
            )
        val visualLane = project.toVisualTimelinePlaybackSpec(listOf(track))!!.lanes.single()
        val liveLane = project.toLiveEnginePlaybackSpec(listOf(track), MixTransportMs(0L))!!.lanes.single()
        assertEquals(144L, visualLane.timelineClipStartMs)
        assertEquals(82L, liveLane.timelineClipStartMs)
    }

    @Test
    fun `scheduling session end is zero when any track loops`() {
        val tracks =
            listOf(
                TrackEntity(id = "a", projectId = "p", wavFilePath = "a.wav", duration = 10_000L),
                TrackEntity(id = "b", projectId = "p", wavFilePath = "b.wav", duration = 30_000L, isLoop = true),
            )
        assertEquals(0L, sessionPlaybackSchedulingEndMsForPlayback(tracks))
    }
}
