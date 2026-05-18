package com.georgv.audioworkstation.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelinePlayheadTest {

    @Test
    fun `tap at left edge maps to zero`() {
        assertEquals(0f, timelinePlayheadFractionFromWaveformX(0f, 200f), 0.0001f)
        assertEquals(0L, timelinePlayheadPositionMs(0f, 8_000L))
    }

    @Test
    fun `tap at right edge of waveform area maps to base duration`() {
        val waveformWidthPx = 200f
        assertEquals(1f, timelinePlayheadFractionFromWaveformX(waveformWidthPx, waveformWidthPx), 0.0001f)
        assertEquals(8_000L, timelinePlayheadPositionMs(1f, 8_000L))
    }

    @Test
    fun `drag fraction clamps inside waveform width`() {
        assertEquals(0f, timelinePlayheadFractionFromWaveformX(-12f, 100f), 0.0001f)
        assertEquals(1f, timelinePlayheadFractionFromWaveformX(140f, 100f), 0.0001f)
    }

    @Test
    fun `metadata width is excluded from waveform mapping`() {
        val laneWidthPx = 100f
        val metrics =
            TimelinePlayheadWaveformMetrics(
                waveformTimelineWidthPx = laneWidthPx * TimelineWaveformWidthFraction,
            )

        assertEquals(
            1f,
            metrics.fractionFromLocalXPx(metrics.waveformTimelineWidthPx),
            0.0001f,
        )
        assertEquals(
            metrics.waveformTimelineWidthPx,
            metrics.xPxForFraction(1f),
            0.0001f,
        )
    }

    @Test
    fun `playhead fraction is consistent between position and x`() {
        val baseDurationMs = 10_000L
        val positionMs = 2_500L
        val fraction = timelinePlayheadFraction(positionMs, baseDurationMs)
        val waveformWidthPx = 160f

        assertEquals(0.25f, fraction, 0.0001f)
        assertEquals(
            timelinePlayheadXInWaveformArea(fraction, waveformWidthPx),
            40f,
            0.0001f,
        )
        assertEquals(
            positionMs,
            timelinePlayheadPositionMs(
                timelinePlayheadFractionFromWaveformX(40f, waveformWidthPx),
                baseDurationMs,
            ),
        )
    }

    @Test
    fun `base duration changes clamp playhead`() {
        assertEquals(3_000L, timelinePlayheadClampedPositionMs(7_500L, 3_000L))
        assertEquals(0L, timelinePlayheadClampedPositionMs(-500L, 3_000L))
    }
}
