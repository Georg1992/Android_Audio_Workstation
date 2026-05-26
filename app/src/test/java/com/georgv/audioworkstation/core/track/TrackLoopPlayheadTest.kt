package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.core.audio.isLaneAudibleAtPlayhead
import com.georgv.audioworkstation.core.audio.laneSourceOffsetMs
import com.georgv.audioworkstation.core.audio.laneSourceReadOffsetMs
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.buildProjectTimelineProjection
import com.georgv.audioworkstation.ui.components.projectTimelineClips
import com.georgv.audioworkstation.ui.components.sessionTimelineEndMsForPlayback
import com.georgv.audioworkstation.ui.components.sessionTimelineEndMsForTracks
import com.georgv.audioworkstation.ui.components.timelineClipEffectiveTimelineEndMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            2_500L,
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
