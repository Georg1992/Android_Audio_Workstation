package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.sessionTimelineEndMsForTracks
import com.georgv.audioworkstation.ui.components.timelineClipEndMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelinePlaybackOffsetRealityCheckTest {

    @Test
    fun `scheduling contract clip at 0s playhead 7s seeks source at 7s`() {
        assertEquals(7_000L, laneSourceOffsetMs(playheadMs = 7_000L, clipStartMs = 0L))
    }

    @Test
    fun `scheduling contract clip at 5s playhead 7s seeks source at 2s`() {
        assertEquals(2_000L, laneSourceOffsetMs(playheadMs = 7_000L, clipStartMs = 5_000L))
    }

    @Test
    fun `scheduling contract clip at 12s playhead 7s is not yet audible`() {
        assertFalse(isLaneAudibleAtPlayhead(playheadMs = 7_000L, clipStartMs = 12_000L, clipDurationMs = 5_000L))
        assertEquals(0L, laneSourceOffsetMs(playheadMs = 7_000L, clipStartMs = 12_000L))
    }

    @Test
    fun `timeline duration uses max startOffsetMs plus durationMs`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "long",
                    projectId = "p",
                    wavFilePath = "long.wav",
                    duration = 20_000L,
                    timelineStartOffsetMs = 0L,
                ),
                TrackEntity(
                    id = "late",
                    projectId = "p",
                    wavFilePath = "late.wav",
                    duration = 3_000L,
                    timelineStartOffsetMs = 30_000L,
                ),
            )
        assertEquals(33_000L, sessionTimelineEndMsForTracks(tracks))
    }

    @Test
    fun `toMultiPlaybackSpec exposes per lane timeline clip start and duration`() {
        val project = ProjectEntity(id = "p", sampleRate = 48_000)
        val tracks =
            listOf(
                TrackEntity(
                    id = "b",
                    projectId = "p",
                    wavFilePath = "b.wav",
                    duration = 10_000L,
                    timelineStartOffsetMs = 5_000L,
                ),
            )
        val spec = project.toMultiPlaybackSpec(tracks)!!.copy(startPositionMs = 7_000L)
        val lane = spec.lanes.single()

        assertEquals(5_000L, lane.timelineClipStartMs)
        assertEquals(10_000L, lane.timelineClipDurationMs)
        assertEquals(2_000L, laneSourceOffsetMs(spec.startPositionMs, lane.timelineClipStartMs))
        assertTrue(
            isLaneAudibleAtPlayhead(
                spec.startPositionMs,
                lane.timelineClipStartMs,
                lane.timelineClipDurationMs,
            ),
        )
    }
}
