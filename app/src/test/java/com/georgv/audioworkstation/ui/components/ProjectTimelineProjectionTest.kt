package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTimelineProjectionTest {

    @Test
    fun `empty project timeline duration is zero regardless of playhead`() {
        val projection =
            buildIdleProjection(playheadPositionMs = 45_000L)

        assertEquals(0L, projection.baseTimelineDurationMs)
        assertEquals(0L, projection.visibleTimelineDurationMs)
        assertTrue(projection.clips.isEmpty())
    }

    @Test
    fun `idle visible timeline ignores playhead beyond base`() {
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
                extendVisibleTimelineForAllLoopedPlayback = false,
                extendVisibleTimelineForRecording = false,
            )

        assertEquals(10_000L, projection.baseTimelineDurationMs)
        assertEquals(10_000L, projection.visibleTimelineDurationMs)
    }

    @Test
    fun `all-looped playback extends visible timeline when playhead passes base`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 10_000L,
                    isLoop = true,
                ),
            )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                activeRecording = null,
                playheadPositionMs = 25_000L,
                extendVisibleTimelineForAllLoopedPlayback = true,
                extendVisibleTimelineForRecording = false,
            )

        assertEquals(10_000L, projection.baseTimelineDurationMs)
        assertEquals(25_000L, projection.visibleTimelineDurationMs)
    }

    @Test
    fun `stopping playback restores visible timeline to base`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 10_000L,
                    isLoop = true,
                ),
            )
        val playing =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                activeRecording = null,
                playheadPositionMs = 25_000L,
                extendVisibleTimelineForAllLoopedPlayback = true,
                extendVisibleTimelineForRecording = false,
            )
        val idle =
            buildIdleProjection(
                tracks = tracks,
                playheadPositionMs = 25_000L,
            )

        assertEquals(25_000L, playing.visibleTimelineDurationMs)
        assertEquals(10_000L, idle.visibleTimelineDurationMs)
        assertEquals(idle.baseTimelineDurationMs, idle.visibleTimelineDurationMs)
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
                extendVisibleTimelineForAllLoopedPlayback = false,
                extendVisibleTimelineForRecording = true,
            )

        val clip = projection.clipsByLaneId["rec"]
        assertNotNull(clip)
        assertEquals(0L, clip!!.startOffsetMs)
        assertEquals(12_000L, clip.durationMs)
        assertTrue(clip.isActiveRecording)
        assertTrue(clip.isTimelineBase)
        assertEquals(12_000L, projection.visibleTimelineDurationMs)
    }

    @Test
    fun `recording from middle expands visible timeline to start plus elapsed`() {
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
                extendVisibleTimelineForAllLoopedPlayback = false,
                extendVisibleTimelineForRecording = true,
            )

        assertEquals(5_000L, projection.baseTimelineDurationMs)
        assertEquals(38_000L, projection.visibleTimelineDurationMs)
        assertTrue(projection.clipsByLaneId["rec"]!!.isTimelineBase)
    }

    @Test
    fun `recording past prior timeline end grows visible duration`() {
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
                extendVisibleTimelineForAllLoopedPlayback = false,
                extendVisibleTimelineForRecording = true,
            )

        assertEquals(10_000L, projection.baseTimelineDurationMs)
        assertEquals(15_000L, projection.visibleTimelineDurationMs)
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
        val projection = buildIdleProjection(tracks = tracks)

        assertEquals(33_000L, projection.baseTimelineDurationMs)
        assertEquals(33_000L, projection.visibleTimelineDurationMs)
        assertTrue(projection.clipsByLaneId["late"]!!.isTimelineBase)
        assertEquals(false, projection.clipsByLaneId["long"]!!.isTimelineBase)
    }

    @Test
    fun `looped track with shortened loop end keeps full duration base timeline`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 20_000L,
                    isLoop = true,
                    loopStartMs = 0L,
                    loopEndMs = 12_000L,
                ),
                TrackEntity(
                    id = "longer",
                    projectId = "p",
                    wavFilePath = "longer.wav",
                    duration = 15_000L,
                ),
            )
        val projection = buildIdleProjection(tracks = tracks)

        assertEquals(20_000L, projection.baseTimelineDurationMs)
        assertEquals(20_000L, projection.visibleTimelineDurationMs)
        assertTrue(projection.clipsByLaneId["loop"]!!.isTimelineBase)
        assertFalse(projection.clipsByLaneId["longer"]!!.isTimelineBase)
    }

    @Test
    fun `session timeline end uses full placement not loop end`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 20_000L,
                    isLoop = true,
                    loopEndMs = 12_000L,
                ),
            )

        assertEquals(20_000L, sessionTimelineEndMsForTracks(tracks))
    }

    @Test
    fun `shouldExtendVisibleTimelineForAllLoopedPlayback requires active loop session`() {
        val tracks =
            listOf(
                TrackEntity(id = "a", projectId = "p", wavFilePath = "a.wav", duration = 10_000L, isLoop = true),
                TrackEntity(id = "b", projectId = "p", wavFilePath = "b.wav", duration = 10_000L, isLoop = false),
            )

        assertTrue(
            shouldExtendVisibleTimelineForAllLoopedPlayback(
                playbackSessionActive = true,
                sessionTrackIds = setOf("a"),
                tracks = tracks,
            ),
        )
        assertFalse(
            shouldExtendVisibleTimelineForAllLoopedPlayback(
                playbackSessionActive = true,
                sessionTrackIds = setOf("a", "b"),
                tracks = tracks,
            ),
        )
    }

    private fun buildIdleProjection(
        tracks: List<TrackEntity> = emptyList(),
        playheadPositionMs: Long = 0L,
    ): ProjectTimelineProjection =
        buildProjectTimelineProjection(
            tracks = tracks,
            waveformStatesByTrackId = emptyMap(),
            activeRecording = null,
            playheadPositionMs = playheadPositionMs,
            extendVisibleTimelineForAllLoopedPlayback = false,
            extendVisibleTimelineForRecording = false,
        )
}
