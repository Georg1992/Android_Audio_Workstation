package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.core.audio.isLaneAudibleAtPlayhead
import com.georgv.audioworkstation.core.audio.laneSourceOffsetMs
import com.georgv.audioworkstation.core.audio.laneSourceReadOffsetMs
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.buildProjectTimelineProjection
import com.georgv.audioworkstation.ui.components.projectTimelineClips
import com.georgv.audioworkstation.ui.components.sessionTimelineEndMsForPlayback
import com.georgv.audioworkstation.ui.components.sessionTimelineEndMsForTracks
import com.georgv.audioworkstation.ui.components.shouldExtendVisibleTimelineForAllLoopedPlayback
import com.georgv.audioworkstation.ui.components.timelineClipEffectiveTimelineEndMs
import com.georgv.audioworkstation.ui.components.timelinePlayheadClampedPositionMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLoopPlayheadTest {

    @Test
    fun `non-loop local playhead equals global minus timeline offset`() {
        assertEquals(
            2_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = 7_000L,
                timelineStartOffsetMs = 5_000L,
                sourceDurationMs = 20_000L,
                loopEnabled = false,
                loopStartMs = 0L,
                loopEndMs = 20_000L,
            ),
        )
        assertEquals(
            2_000L,
            trackLocalTimelineMs(globalPlayheadMs = 7_000L, timelineStartOffsetMs = 5_000L),
        )
    }

    @Test
    fun `looped local playhead maps global at clip start to loopStart`() {
        assertEquals(
            3_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = 5_000L,
                timelineStartOffsetMs = 5_000L,
                sourceDurationMs = 20_000L,
                loopEnabled = true,
                loopStartMs = 3_000L,
                loopEndMs = 10_000L,
            ),
        )
    }

    @Test
    fun `local playhead hidden until global reaches clip timeline start`() {
        assertFalse(
            trackLocalPlayheadVisibleInClip(
                globalPlayheadMs = 9_999L,
                timelineStartOffsetMs = 10_000L,
                clipDurationMs = 30_000L,
            ),
        )
        assertFalse(
            trackLocalPlayheadVisibleInClip(
                globalPlayheadMs = 0L,
                timelineStartOffsetMs = 10_000L,
                clipDurationMs = 30_000L,
            ),
        )
        assertTrue(
            trackLocalPlayheadVisibleInClip(
                globalPlayheadMs = 10_000L,
                timelineStartOffsetMs = 10_000L,
                clipDurationMs = 30_000L,
            ),
        )
        assertTrue(
            trackLocalPlayheadVisibleInClip(
                globalPlayheadMs = 15_000L,
                timelineStartOffsetMs = 10_000L,
                clipDurationMs = 30_000L,
            ),
        )
    }

    @Test
    fun `looped local playhead stays visible after global exceeds clip duration`() {
        assertTrue(
            trackLocalPlayheadVisibleInClip(
                globalPlayheadMs = 50_001L,
                timelineStartOffsetMs = 20_000L,
                clipDurationMs = 30_000L,
                loopEnabled = true,
            ),
        )
        assertEquals(
            4_001L,
            trackSourcePlayheadMsForClipTimelineWindow(
                globalPlayheadMs = 50_001L,
                timelineStartOffsetMs = 20_000L,
                sourceDurationMs = 30_000L,
                loopEnabled = true,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
            ),
        )
    }

    @Test
    fun `looped local playhead stays visible after global exceeds base timeline`() {
        val baseTimelineMs = 20_000L
        val globalPastBase = baseTimelineMs + 2_000L
        assertTrue(
            trackLocalPlayheadVisibleInClip(
                globalPlayheadMs = globalPastBase,
                timelineStartOffsetMs = 0L,
                clipDurationMs = 20_000L,
                loopEnabled = true,
            ),
        )
        assertEquals(
            3_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = globalPastBase,
                timelineStartOffsetMs = 0L,
                sourceDurationMs = 20_000L,
                loopEnabled = true,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
            ),
        )
    }

    @Test
    fun `looped source playhead wraps across many cycles`() {
        val loopStartMs = 1_000L
        val loopEndMs = 4_000L
        val loopLengthMs = loopEndMs - loopStartMs
        val timelineStartMs = 5_000L
        repeat(12) { cycle ->
            val globalMs = timelineStartMs + cycle * loopLengthMs + 500L
            assertEquals(
                loopStartMs + 500L,
                trackSourcePlayheadMs(
                    globalPlayheadMs = globalMs,
                    timelineStartOffsetMs = timelineStartMs,
                    sourceDurationMs = 20_000L,
                    loopEnabled = true,
                    loopStartMs = loopStartMs,
                    loopEndMs = loopEndMs,
                ),
            )
        }
    }

    @Test
    fun `mixed loop session extends visible timeline past base for advancing playhead`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "one-shot",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                ),
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "b.wav",
                    duration = 30_000L,
                    isLoop = true,
                    loopStartMs = 2_000L,
                    loopEndMs = 9_000L,
                ),
            )
        val playheadMs = 35_000L
        assertTrue(
            shouldExtendVisibleTimelineForAllLoopedPlayback(
                playbackSessionActive = true,
                selectedTrackIds = setOf("one-shot", "loop"),
                tracks = tracks,
            ),
        )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                selectedTrackIds = tracks.map { it.id }.toSet(),
                activeRecording = null,
                playheadPositionMs = playheadMs,
                extendVisibleTimelineForAllLoopedPlayback = true,
                extendVisibleTimelineForRecording = false,
            )
        assertEquals(30_000L, projection.baseTimelineDurationMs)
        assertEquals(playheadMs, projection.visibleTimelineDurationMs)
        assertEquals(
            playheadMs,
            timelinePlayheadClampedPositionMs(playheadMs, projection.visibleTimelineDurationMs),
        )
        assertEquals(
            2_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = playheadMs,
                timelineStartOffsetMs = 0L,
                sourceDurationMs = 30_000L,
                loopEnabled = true,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
            ),
        )
    }

    @Test
    fun `non-loop playhead hidden after clip timeline window ends`() {
        assertFalse(
            trackLocalPlayheadVisibleInClip(
                globalPlayheadMs = 50_001L,
                timelineStartOffsetMs = 20_000L,
                clipDurationMs = 30_000L,
                loopEnabled = false,
                limitToClipTimelineWindow = true,
            ),
        )
        assertNull(
            trackSourcePlayheadMsForClipTimelineWindow(
                globalPlayheadMs = 50_001L,
                timelineStartOffsetMs = 20_000L,
                sourceDurationMs = 30_000L,
                loopEnabled = false,
                loopStartMs = 0L,
                loopEndMs = 30_000L,
            ),
        )
    }

    @Test
    fun `zoomed clip playhead only visible within clip timeline window`() {
        assertNull(
            trackSourcePlayheadMsForClipTimelineWindow(
                globalPlayheadMs = 19_999L,
                timelineStartOffsetMs = 20_000L,
                sourceDurationMs = 30_000L,
                loopEnabled = false,
                loopStartMs = 0L,
                loopEndMs = 30_000L,
            ),
        )
        assertEquals(
            5_000L,
            trackSourcePlayheadMsForClipTimelineWindow(
                globalPlayheadMs = 25_000L,
                timelineStartOffsetMs = 20_000L,
                sourceDurationMs = 30_000L,
                loopEnabled = false,
                loopStartMs = 0L,
                loopEndMs = 30_000L,
            ),
        )
        assertEquals(
            30_000L,
            trackSourcePlayheadMsForClipTimelineWindow(
                globalPlayheadMs = 50_000L,
                timelineStartOffsetMs = 20_000L,
                sourceDurationMs = 30_000L,
                loopEnabled = false,
                loopStartMs = 0L,
                loopEndMs = 30_000L,
            ),
        )
        assertNull(
            trackSourcePlayheadMsForClipTimelineWindow(
                globalPlayheadMs = 50_001L,
                timelineStartOffsetMs = 20_000L,
                sourceDurationMs = 30_000L,
                loopEnabled = false,
                loopStartMs = 0L,
                loopEndMs = 30_000L,
            ),
        )
    }

    @Test
    fun `looped local playhead wraps from loopEnd to loopStart`() {
        val loopStartMs = 2_000L
        val loopEndMs = 9_000L
        val loopLengthMs = loopEndMs - loopStartMs

        val atLoopEndBoundary =
            trackSourcePlayheadMs(
                globalPlayheadMs = 5_000L + loopLengthMs,
                timelineStartOffsetMs = 5_000L,
                sourceDurationMs = 20_000L,
                loopEnabled = true,
                loopStartMs = loopStartMs,
                loopEndMs = loopEndMs,
            )
        assertEquals(loopStartMs, atLoopEndBoundary)

        val afterWrap =
            trackSourcePlayheadMs(
                globalPlayheadMs = 5_000L + loopLengthMs + 500L,
                timelineStartOffsetMs = 5_000L,
                sourceDurationMs = 20_000L,
                loopEnabled = true,
                loopStartMs = loopStartMs,
                loopEndMs = loopEndMs,
            )
        assertEquals(loopStartMs + 500L, afterWrap)
    }

    @Test
    fun `global playhead mapping is independent of loop wrap`() {
        val globalBefore = 12_000L
        val globalAfter =
            trackLocalTimelineMs(
                globalPlayheadMs = 12_000L + 7_000L,
                timelineStartOffsetMs = 5_000L,
            ) + 5_000L
        assertEquals(19_000L, globalAfter)
        assertTrue(globalAfter > globalBefore)
    }

    @Test
    fun `base timeline does not shrink when loop end is shortened`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 20_000L,
                    isLoop = true,
                    loopEndMs = 8_000L,
                ),
                TrackEntity(
                    id = "longer",
                    projectId = "p",
                    wavFilePath = "longer.wav",
                    duration = 15_000L,
                ),
            )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                selectedTrackIds = tracks.map { it.id }.toSet(),
                activeRecording = null,
                playheadPositionMs = 0L,
                extendVisibleTimelineForAllLoopedPlayback = false,
                extendVisibleTimelineForRecording = false,
            )

        assertEquals(20_000L, projection.baseTimelineDurationMs)
        assertEquals(20_000L, projection.visibleTimelineDurationMs)
        assertEquals(
            20_000L,
            timelineClipEffectiveTimelineEndMs(projection.clipsByLaneId["loop"]!!),
        )
    }

    @Test
    fun `loop clip keeps full duration for waveform layout`() {
        val clip =
            projectTimelineClips(
                tracks =
                    listOf(
                        TrackEntity(
                            id = "loop",
                            projectId = "p",
                            wavFilePath = "loop.wav",
                            duration = 20_000L,
                            isLoop = true,
                            loopStartMs = 2_000L,
                            loopEndMs = 9_000L,
                        ),
                    ),
                waveformStatesByTrackId = emptyMap(),
            ).single()

        assertEquals(20_000L, clip.durationMs)
        assertEquals(2_000L, clip.effectiveStartMs)
        assertEquals(9_000L, clip.effectiveEndMs)
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
                    loopEndMs = 8_000L,
                ),
            )

        assertEquals(20_000L, sessionTimelineEndMsForTracks(tracks))
    }

    @Test
    fun `mixed loop project keeps open session end for playback`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "one-shot",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                ),
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "b.wav",
                    duration = 30_000L,
                    isLoop = true,
                ),
            )

        assertEquals(0L, sessionTimelineEndMsForPlayback(tracks))
    }

    @Test
    fun `all-looped playback session end is open`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                    isLoop = true,
                    loopEndMs = 5_000L,
                ),
            )

        assertEquals(0L, sessionTimelineEndMsForPlayback(tracks))
    }

    @Test
    fun `lane source read offset wraps for looped playback lane`() {
        assertEquals(
            7_500L,
            laneSourceReadOffsetMs(
                playheadMs = 5_500L,
                clipStartMs = 5_000L,
                loopEnabled = true,
                loopSourceStartMs = 2_000L,
                loopSourceEndMs = 9_000L,
            ),
        )
    }

    @Test
    fun `looped lane stays audible after one-shot clip would have ended`() {
        assertTrue(
            isLaneAudibleAtPlayhead(
                playheadMs = 25_000L,
                clipStartMs = 0L,
                clipDurationMs = 10_000L,
                loopEnabled = true,
            ),
        )
        assertFalse(
            isLaneAudibleAtPlayhead(
                playheadMs = 25_000L,
                clipStartMs = 0L,
                clipDurationMs = 10_000L,
                loopEnabled = false,
            ),
        )
    }

    @Test
    fun `non-loop lane source offset unchanged from clip start delta`() {
        assertEquals(
            2_000L,
            laneSourceOffsetMs(playheadMs = 7_000L, clipStartMs = 5_000L),
        )
    }
}
