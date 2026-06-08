package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.TimelineClip
import com.georgv.audioworkstation.ui.components.TimelineLaneScaleMode
import com.georgv.audioworkstation.ui.components.WaveformState
import com.georgv.audioworkstation.ui.components.clipStartFractionOnWaveformArea
import com.georgv.audioworkstation.ui.components.clipWidthFractionOnWaveformArea
import com.georgv.audioworkstation.ui.components.timelineLaneScaleForLoopPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopPlaybackProjectionTest {

    private fun loopTrack(
        id: String = "loop",
        timelineStartOffsetMs: Long = 0L,
        durationMs: Long = 20_000L,
        loopStartMs: Long = 2_000L,
        loopEndMs: Long = 9_000L,
    ) =
        TrackEntity(
            id = id,
            projectId = "p",
            wavFilePath = "$id.wav",
            duration = durationMs,
            timelineStartOffsetMs = timelineStartOffsetMs,
            isLoop = true,
            loopStartMs = loopStartMs,
            loopEndMs = loopEndMs,
        )

    @Test
    fun `track loop playback position wraps within track loop length`() {
        assertEquals(
            1_500L,
            trackLoopPlaybackPositionMs(
                rawPlayheadMs = 8_500L,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
            ),
        )
        assertEquals(
            0L,
            trackLoopPlaybackPositionMs(
                rawPlayheadMs = 14_000L,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
            ),
        )
    }

    @Test
    fun `loop playback start always resets to zero`() {
        val tracks = listOf(loopTrack())
        assertEquals(
            0L,
            playbackStartPositionMsForTracks(
                scrubbedPlayheadMs = 15_000L,
                timelineVisibleDurationMs = 20_000L,
                tracks = tracks,
            ),
        )
    }

    @Test
    fun `non-loop playback keeps scrubbed start position`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                ),
            )
        assertEquals(
            5_000L,
            playbackStartPositionMsForTracks(
                scrubbedPlayheadMs = 5_000L,
                timelineVisibleDurationMs = 10_000L,
                tracks = tracks,
            ),
        )
    }

    @Test
    fun `loop range fills waveform container during loop playback`() {
        val clip =
            TimelineClip(
                clipId = "loop",
                laneId = "loop",
                startOffsetMs = 0L,
                durationMs = 20_000L,
                waveformState = WaveformState.NoWaveform,
                isTimelineBase = true,
                formattedDuration = "0:20",
                isLoop = true,
                effectiveStartMs = 2_000L,
                effectiveEndMs = 9_000L,
            )
        val scale =
            timelineLaneScaleForLoopPlayback(
                laneLayoutDurationMs = 20_000L,
                clip = clip,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
            )

        assertEquals(TimelineLaneScaleMode.LoopPlayback, scale.mode)
        assertEquals(0f, scale.clipStartFractionOnWaveformArea(), 0.0001f)
        assertEquals(1f, scale.clipWidthFractionOnWaveformArea(), 0.0001f)
        assertEquals(7_000L, scale.sourceDurationMs)
        assertEquals(0f, loopPlaybackSourceMsToXInClip(2_000L, 800f, 2_000L, 9_000L), 0.0001f)
        assertEquals(800f, loopPlaybackSourceMsToXInClip(9_000L, 800f, 2_000L, 9_000L), 0.0001f)
        assertEquals(400f, loopPlaybackSourceMsToXInClip(5_500L, 800f, 2_000L, 9_000L), 0.0001f)
    }

    @Test
    fun `clip local playhead at loop start when clip begins at timeline zero`() {
        assertEquals(
            2_000L,
            loopPlaybackClipLocalSourceMs(
                loopPlaybackPositionMs = 0L,
                clipDurationMs = 20_000L,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
            ),
        )
    }

    @Test
    fun `clip local playhead when clip starts inside loop range on timeline`() {
        val track = loopTrack(timelineStartOffsetMs = 5_000L)
        assertEquals(
            2_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = 0L,
                timelineStartOffsetMs = track.timelineStartOffsetMs,
                sourceDurationMs = track.duration!!,
                loopEnabled = true,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
                loopPlaybackActive = true,
            ),
        )
        assertEquals(
            4_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = 2_000L,
                timelineStartOffsetMs = track.timelineStartOffsetMs,
                sourceDurationMs = track.duration!!,
                loopEnabled = true,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
                loopPlaybackActive = true,
            ),
        )
    }

    @Test
    fun `clip local playhead when clip starts before loop range on timeline`() {
        val track = loopTrack(timelineStartOffsetMs = 0L)
        assertEquals(
            2_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = 0L,
                timelineStartOffsetMs = track.timelineStartOffsetMs,
                sourceDurationMs = track.duration!!,
                loopEnabled = true,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
                loopPlaybackActive = true,
            ),
        )
        assertEquals(
            5_000L,
            trackSourcePlayheadMs(
                globalPlayheadMs = 3_000L,
                timelineStartOffsetMs = track.timelineStartOffsetMs,
                sourceDurationMs = track.duration!!,
                loopEnabled = true,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
                loopPlaybackActive = true,
            ),
        )
    }

    @Test
    fun `clip local playhead clamps when timeline offset places clip after loop anchor`() {
        val local =
            loopPlaybackClipLocalSourceMs(
                loopPlaybackPositionMs = 500L,
                clipDurationMs = 3_000L,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
            )
        assertEquals(2_500L, local)
        assertTrue(local <= 3_000L)
    }

    @Test
    fun `seeking global playhead updates all local playheads consistently`() {
        val loopA = loopTrack(id = "a", loopStartMs = 1_000L, loopEndMs = 4_000L)
        val loopB =
            loopTrack(
                id = "b",
                timelineStartOffsetMs = 3_000L,
                loopStartMs = 2_000L,
                loopEndMs = 8_000L,
            )
        val globalMs = 2_500L

        val localA =
            trackSourcePlayheadMs(
                globalPlayheadMs = globalMs,
                timelineStartOffsetMs = loopA.timelineStartOffsetMs,
                sourceDurationMs = loopA.duration!!,
                loopEnabled = true,
                loopStartMs = 1_000L,
                loopEndMs = 4_000L,
                loopPlaybackActive = true,
            )
        val localB =
            trackSourcePlayheadMs(
                globalPlayheadMs = globalMs,
                timelineStartOffsetMs = loopB.timelineStartOffsetMs,
                sourceDurationMs = loopB.duration!!,
                loopEnabled = true,
                loopStartMs = 2_000L,
                loopEndMs = 8_000L,
                loopPlaybackActive = true,
            )

        assertEquals(1_000L + 2_500L % 3_000L, localA)
        assertEquals(2_000L + 2_500L % 6_000L, localB)

        val seekMs = 500L
        val seekLocalA =
            trackSourcePlayheadMs(
                globalPlayheadMs = seekMs,
                timelineStartOffsetMs = loopA.timelineStartOffsetMs,
                sourceDurationMs = loopA.duration!!,
                loopEnabled = true,
                loopStartMs = 1_000L,
                loopEndMs = 4_000L,
                loopPlaybackActive = true,
            )
        val seekLocalB =
            trackSourcePlayheadMs(
                globalPlayheadMs = seekMs,
                timelineStartOffsetMs = loopB.timelineStartOffsetMs,
                sourceDurationMs = loopB.duration!!,
                loopEnabled = true,
                loopStartMs = 2_000L,
                loopEndMs = 8_000L,
                loopPlaybackActive = true,
            )
        assertEquals(1_500L, seekLocalA)
        assertEquals(2_500L, seekLocalB)
    }

    @Test
    fun `non-loop clip local playhead uses global minus clip offset`() {
        assertEquals(
            2_000L,
            clipLocalPlayheadMs(
                globalPlayheadMs = 7_000L,
                clipStartOffsetMs = 5_000L,
                clipDurationMs = 10_000L,
            ),
        )
        assertEquals(
            10_000L,
            clipLocalPlayheadMs(
                globalPlayheadMs = 20_000L,
                clipStartOffsetMs = 5_000L,
                clipDurationMs = 10_000L,
            ),
        )
    }

    @Test
    fun `session loop timeline duration uses longest loop in session`() {
        val tracks =
            listOf(
                loopTrack(id = "a", loopStartMs = 0L, loopEndMs = 4_000L),
                loopTrack(id = "b", loopStartMs = 1_000L, loopEndMs = 8_000L),
            )
        assertEquals(
            7_000L,
            sessionLoopTimelineDurationMs(tracks, sessionTrackIds = setOf("a", "b")),
        )
    }
}
