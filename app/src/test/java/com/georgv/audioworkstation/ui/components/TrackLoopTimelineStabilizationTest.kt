package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.core.track.clampLoopRegionMs
import com.georgv.audioworkstation.core.track.loopRegionOverlayFractions
import com.georgv.audioworkstation.core.track.trackSourcePlayheadMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLoopTimelineStabilizationTest {

    @Test
    fun `loop overlay fractions use source duration not global timeline`() {
        val fractions =
            loopRegionOverlayFractions(
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
            )

        assertEquals(0.3f, fractions.startFraction, 0.0001f)
        assertEquals(0.6f, fractions.endFraction, 0.0001f)
    }

    @Test
    fun `handle release clamp keeps same overlay fractions`() {
        val previewStart = 6_000L
        val previewEnd = 12_000L
        val sourceDurationMs = 20_000L
        val before =
            loopRegionOverlayFractions(
                loopStartMs = previewStart,
                loopEndMs = previewEnd,
                sourceDurationMs = sourceDurationMs,
            )
        val (commitStart, commitEnd) =
            clampLoopRegionMs(previewStart, previewEnd, sourceDurationMs)
        val after =
            loopRegionOverlayFractions(
                loopStartMs = commitStart,
                loopEndMs = commitEnd,
                sourceDurationMs = sourceDurationMs,
            )

        assertEquals(before.startFraction, after.startFraction, 0.0001f)
        assertEquals(before.endFraction, after.endFraction, 0.0001f)
    }

    @Test
    fun `local loop playhead maps source position independent of lane layout duration`() {
        val sourcePlayheadMs =
            trackSourcePlayheadMs(
                globalPlayheadMs = 6_000L,
                timelineStartOffsetMs = 5_000L,
                sourceDurationMs = 20_000L,
                loopEnabled = true,
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
            )

        assertEquals(3_000L, sourcePlayheadMs)
    }

    @Test
    fun `lane layout width fraction uses base timeline not extended global timeline`() {
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
        val baseLayout = timelineClipLayout(clip, timelineBaseDurationMs = 20_000L)!!
        val expandedLayout = timelineClipLayout(clip, timelineBaseDurationMs = 40_000L)!!

        assertEquals(1f, baseLayout.widthFraction, 0.0001f)
        assertTrue(expandedLayout.widthFraction < baseLayout.widthFraction)
    }

    @Test
    fun `non-loop lane layout unchanged when global timeline extends`() {
        val clip =
            TimelineClip(
                clipId = "a",
                laneId = "a",
                startOffsetMs = 0L,
                durationMs = 10_000L,
                waveformState = WaveformState.NoWaveform,
                isTimelineBase = true,
                formattedDuration = "0:10",
            )
        val atBase = timelineClipLayout(clip, timelineBaseDurationMs = 10_000L)!!
        val atExpanded = timelineClipLayout(clip, timelineBaseDurationMs = 10_000L)!!

        assertEquals(atBase.widthFraction, atExpanded.widthFraction, 0.0001f)
        assertEquals(atBase.startFraction, atExpanded.startFraction, 0.0001f)
    }
}
