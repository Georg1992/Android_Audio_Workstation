package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTimelineProjectionTest {

    @Test
    fun `timeline duration includes playhead beyond clip ends`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                ),
            )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                activeRecording = null,
                playheadPositionMs = 25_000L,
            )

        assertEquals(25_000L, projection.timelineDurationMs)
    }

    @Test
    fun `active recording participates in timeline and base calculation`() {
        val projection =
            buildProjectTimelineProjection(
                tracks = emptyList(),
                waveformStatesByTrackId = emptyMap(),
                activeRecording =
                    ActiveRecordingTimelineClip(
                        trackId = "rec",
                        startOffsetMs = 0L,
                        elapsedMs = 12_000L,
                    ),
                playheadPositionMs = 12_000L,
            )

        val clip = projection.clipsByLaneId["rec"]
        assertNotNull(clip)
        assertEquals(0L, clip!!.startOffsetMs)
        assertEquals(12_000L, clip.durationMs)
        assertTrue(clip.isActiveRecording)
        assertTrue(clip.isTimelineBase)
        assertEquals(12_000L, projection.timelineDurationMs)
    }

    @Test
    fun `recording from middle expands timeline to start plus elapsed`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "existing",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 5_000L,
                    timelineStartOffsetMs = 0L,
                ),
            )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                activeRecording =
                    ActiveRecordingTimelineClip(
                        trackId = "rec",
                        startOffsetMs = 30_000L,
                        elapsedMs = 8_000L,
                    ),
                playheadPositionMs = 38_000L,
            )

        assertEquals(38_000L, projection.timelineDurationMs)
        assertTrue(projection.clipsByLaneId["rec"]!!.isTimelineBase)
    }

    @Test
    fun `recording past prior timeline end grows shared duration`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                ),
            )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                activeRecording =
                    ActiveRecordingTimelineClip(
                        trackId = "rec",
                        startOffsetMs = 0L,
                        elapsedMs = 15_000L,
                    ),
                playheadPositionMs = 15_000L,
            )

        assertEquals(15_000L, projection.timelineDurationMs)
        assertTrue(projection.clipsByLaneId["rec"]!!.isTimelineBase)
    }

    @Test
    fun `base track is furthest clip end not longest duration only`() {
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
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                activeRecording = null,
                playheadPositionMs = 0L,
            )

        assertEquals(33_000L, projection.timelineDurationMs)
        assertTrue(projection.clipsByLaneId["late"]!!.isTimelineBase)
        assertEquals(false, projection.clipsByLaneId["long"]!!.isTimelineBase)
    }
}
