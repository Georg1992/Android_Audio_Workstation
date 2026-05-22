package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timeline offset playback spec contract (Kotlin → JNI → native arm).
 */
class TimelinePlaybackOffsetTest {

    private val project = ProjectEntity(id = "p", sampleRate = 48_000)

    @Test
    fun `clipStart 5000 playhead 0 lane silent until clip then source 0`() {
        val spec = spec(playheadMs = 0L, clipStartMs = 5_000L, durationMs = 8_000L)
        val lane = spec.lanes.single()
        assertEquals(0L, laneSourceOffsetMs(spec.startPositionMs, lane.timelineClipStartMs))
        assertFalse(isLaneAudibleAtPlayhead(spec.startPositionMs, lane.timelineClipStartMs, lane.timelineClipDurationMs))
    }

    @Test
    fun `clipStart 5000 playhead 7000 lane source offset 2000`() {
        val spec = spec(playheadMs = 7_000L, clipStartMs = 5_000L, durationMs = 8_000L)
        val lane = spec.lanes.single()
        assertEquals(2_000L, laneSourceOffsetMs(spec.startPositionMs, lane.timelineClipStartMs))
        assertTrue(isLaneAudibleAtPlayhead(spec.startPositionMs, lane.timelineClipStartMs, lane.timelineClipDurationMs))
    }

    @Test
    fun `clipStart 12000 playhead 7000 lane not audible yet`() {
        val spec = spec(playheadMs = 7_000L, clipStartMs = 12_000L, durationMs = 5_000L)
        val lane = spec.lanes.single()
        assertEquals(0L, laneSourceOffsetMs(spec.startPositionMs, lane.timelineClipStartMs))
        assertFalse(isLaneAudibleAtPlayhead(spec.startPositionMs, lane.timelineClipStartMs, lane.timelineClipDurationMs))
    }

    @Test
    fun `playhead after clip end lane not audible`() {
        val spec = spec(playheadMs = 20_000L, clipStartMs = 5_000L, durationMs = 8_000L)
        val lane = spec.lanes.single()
        assertFalse(isLaneAudibleAtPlayhead(spec.startPositionMs, lane.timelineClipStartMs, lane.timelineClipDurationMs))
    }

    @Test
    fun `multiple lanes each carry distinct clip start for native arrays`() {
        val tracks =
            listOf(
                track(id = "a", clipStartMs = 0L, durationMs = 10_000L),
                track(id = "b", clipStartMs = 5_000L, durationMs = 6_000L),
                track(id = "c", clipStartMs = 12_000L, durationMs = 4_000L),
            )
        val spec = project.toMultiPlaybackSpec(tracks)!!.copy(startPositionMs = 7_000L)
        assertEquals(listOf(0L, 5_000L, 12_000L), spec.lanes.map { it.timelineClipStartMs })
        assertEquals(listOf(10_000L, 6_000L, 4_000L), spec.lanes.map { it.timelineClipDurationMs })
        assertEquals(7_000L, laneSourceOffsetMs(spec.startPositionMs, spec.lanes[0].timelineClipStartMs))
        assertEquals(2_000L, laneSourceOffsetMs(spec.startPositionMs, spec.lanes[1].timelineClipStartMs))
        assertEquals(0L, laneSourceOffsetMs(spec.startPositionMs, spec.lanes[2].timelineClipStartMs))
        assertTrue(isLaneAudibleAtPlayhead(spec.startPositionMs, spec.lanes[0].timelineClipStartMs, spec.lanes[0].timelineClipDurationMs))
        assertTrue(isLaneAudibleAtPlayhead(spec.startPositionMs, spec.lanes[1].timelineClipStartMs, spec.lanes[1].timelineClipDurationMs))
        assertFalse(isLaneAudibleAtPlayhead(spec.startPositionMs, spec.lanes[2].timelineClipStartMs, spec.lanes[2].timelineClipDurationMs))
    }

    private fun spec(playheadMs: Long, clipStartMs: Long, durationMs: Long): MultiPlaybackSpec =
        project.toMultiPlaybackSpec(
            listOf(track(id = "t", clipStartMs = clipStartMs, durationMs = durationMs)),
        )!!.copy(startPositionMs = playheadMs)

    private fun track(id: String, clipStartMs: Long, durationMs: Long): TrackEntity =
        TrackEntity(
            id = id,
            projectId = "p",
            wavFilePath = "$id.wav",
            duration = durationMs,
            timelineStartOffsetMs = clipStartMs,
        )
}
