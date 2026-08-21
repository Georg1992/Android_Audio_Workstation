package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.core.audio.playbackStartAllowedAtPlayhead
import com.georgv.audioworkstation.core.track.effectiveLoopEndMs
import com.georgv.audioworkstation.core.track.effectiveLoopStartMs
import com.georgv.audioworkstation.core.track.trackSourcePlayheadMs
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiLoopPlaybackProjectionTest {

    @Test
    fun `two loop tracks extend visible timeline during all-loop playback`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                    isLoop = true,
                    loopStartMs = 1_000L,
                    loopEndMs = 4_000L,
                ),
                TrackEntity(
                    id = "b",
                    projectId = "p",
                    wavFilePath = "b.wav",
                    duration = 8_000L,
                    isLoop = true,
                    loopStartMs = 2_000L,
                    loopEndMs = 6_000L,
                ),
            )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                selectedTrackIds = tracks.map { it.id }.toSet(),
                activeRecording = null,
                playheadPositionMs = 22_000L,
                extendVisibleTimelineForAllLoopedPlayback = true,
                extendVisibleTimelineForRecording = false,
            )

        assertEquals(10_000L, projection.baseTimelineDurationMs)
        assertEquals(22_000L, projection.visibleTimelineDurationMs)
        assertTrue(
            shouldExtendVisibleTimelineForAllLoopedPlayback(
                playbackSessionActive = true,
                selectedTrackIds = setOf("a", "b"),
                tracks = tracks,
            ),
        )
    }

    @Test
    fun `playback start allowed at base end when any selected track loops`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                    isLoop = true,
                ),
                TrackEntity(
                    id = "b",
                    projectId = "p",
                    wavFilePath = "b.wav",
                    duration = 8_000L,
                    isLoop = true,
                ),
            )

        assertTrue(
            playbackStartAllowedAtPlayhead(
                startPositionMs = 10_000L,
                timelineBaseDurationMs = 10_000L,
                tracks = tracks,
            ),
        )
        assertFalse(
            playbackStartAllowedAtPlayhead(
                startPositionMs = 10_000L,
                timelineBaseDurationMs = 10_000L,
                tracks =
                    listOf(
                        TrackEntity(
                            id = "a",
                            projectId = "p",
                            wavFilePath = "a.wav",
                            duration = 10_000L,
                        ),
                    ),
            ),
        )
    }

    @Test
    fun `local playhead wraps independently for two looped tracks`() {
        val trackA =
            TrackEntity(
                id = "a",
                projectId = "p",
                wavFilePath = "a.wav",
                duration = 20_000L,
                timelineStartOffsetMs = 0L,
                isLoop = true,
                loopStartMs = 1_000L,
                loopEndMs = 4_000L,
            )
        val trackB =
            TrackEntity(
                id = "b",
                projectId = "p",
                wavFilePath = "b.wav",
                duration = 20_000L,
                timelineStartOffsetMs = 0L,
                isLoop = true,
                loopStartMs = 2_000L,
                loopEndMs = 8_000L,
            )

        assertEquals(
            3_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = 5_000L,
                timelineStartOffsetMs = 0L,
                sourceDurationMs = 20_000L,
                loopEnabled = true,
                loopStartMs = trackA.effectiveLoopStartMs(),
                loopEndMs = trackA.effectiveLoopEndMs(),
            ),
        )
        assertEquals(
            7_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = 5_000L,
                timelineStartOffsetMs = 0L,
                sourceDurationMs = 20_000L,
                loopEnabled = true,
                loopStartMs = trackB.effectiveLoopStartMs(),
                loopEndMs = trackB.effectiveLoopEndMs(),
            ),
        )
        assertEquals(
            1_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = 9_000L,
                timelineStartOffsetMs = 0L,
                sourceDurationMs = 20_000L,
                loopEnabled = true,
                loopStartMs = trackA.effectiveLoopStartMs(),
                loopEndMs = trackA.effectiveLoopEndMs(),
            ),
        )
        assertEquals(
            5_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = 9_000L,
                timelineStartOffsetMs = 0L,
                sourceDurationMs = 20_000L,
                loopEnabled = true,
                loopStartMs = trackB.effectiveLoopStartMs(),
                loopEndMs = trackB.effectiveLoopEndMs(),
            ),
        )
    }
}
