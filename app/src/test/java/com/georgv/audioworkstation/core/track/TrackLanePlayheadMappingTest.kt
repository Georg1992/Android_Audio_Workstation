package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.core.timeline.TimelineMinimumBaseDurationMs
import com.georgv.audioworkstation.core.session.TransportPlaybackPhase
import com.georgv.audioworkstation.ui.screens.projects.buildProjectRealtimeUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackLanePlayheadMappingTest {

    @Test
    fun `global overlay duration extends with raw playhead during loop playback`() {
        val duration =
            trackLaneGlobalOverlayTimelineDurationMs(
                laneLayoutDurationMs = 10_000L,
                rawPlayheadMs = 35_000L,
                timelineVisibleDurationMs = 35_000L,
            )
        assertEquals(35_000L, duration)
    }

    @Test
    fun `global overlay duration never below layout minimum`() {
        assertEquals(
            TimelineMinimumBaseDurationMs,
            trackLaneGlobalOverlayTimelineDurationMs(
                laneLayoutDurationMs = 0L,
                rawPlayheadMs = 0L,
                timelineVisibleDurationMs = 0L,
            ),
        )
    }

    @Test
    fun `loop idle source playhead stays within loop bounds and clip duration`() {
        assertEquals(
            8_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = 6_500L,
                timelineStartOffsetMs = 0L,
                sourceDurationMs = 8_000L,
                loopEnabled = true,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
            ),
        )
        assertEquals(
            3_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = 50_000L,
                timelineStartOffsetMs = 0L,
                sourceDurationMs = 20_000L,
                loopEnabled = true,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
            ),
        )
    }

    @Test
    fun `global timeline stays on base during loop playback until playhead extends past base`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 20_000L,
                    isLoop = true,
                    loopStartMs = 1_000L,
                    loopEndMs = 8_000L,
                ),
            )
        val structural =
            com.georgv.audioworkstation.ui.screens.projects.ProjectUiState(
                tracks = tracks,
                selectedTrackIds = setOf("loop"),
                playbackSessionActive = true,
                timelineBaseDurationMs = 20_000L,
                timelineLaneLayoutDurationMs = 20_000L,
            )
        val atStart =
            buildProjectRealtimeUiState(
                playheadMs = 0L,
                mixPlayheadMs = 0L,
                recordingLevel = 0f,
                peakHoldLinear = 0f,
                structural = structural,
                transportPhase = TransportPlaybackPhase.Playing,
            )
        assertEquals(20_000L, atStart.timelineVisibleDurationMs)
        assertEquals(0L, atStart.globalPlayheadPositionMs)

        val pastBase =
            buildProjectRealtimeUiState(
                playheadMs = 22_000L,
                mixPlayheadMs = 22_000L,
                recordingLevel = 0f,
                peakHoldLinear = 0f,
                structural = structural,
                transportPhase = TransportPlaybackPhase.Playing,
            )
        assertEquals(22_000L, pastBase.timelineVisibleDurationMs)
        assertEquals(22_000L, pastBase.globalPlayheadPositionMs)
    }
}
