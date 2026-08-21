package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.core.timeline.timelinePlayheadClampedPositionMs
import com.georgv.audioworkstation.core.timeline.timelinePlayheadFraction
import com.georgv.audioworkstation.core.timeline.timelinePlayheadPositionMs
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelinePlayheadTest {

    @Test
    fun `timelineMsToX maps time to content width`() {
        assertEquals(0f, timelineMsToX(0L, 10_000L, 200f), 0.0001f)
        assertEquals(50f, timelineMsToX(2_500L, 10_000L, 200f), 0.0001f)
        assertEquals(200f, timelineMsToX(10_000L, 10_000L, 200f), 0.0001f)
    }

    @Test
    fun `timelineMsToX respects content start offset`() {
        assertEquals(20f, timelineMsToX(0L, 10_000L, 200f, contentStartPx = 20f), 0.0001f)
        assertEquals(70f, timelineMsToX(2_500L, 10_000L, 200f, contentStartPx = 20f), 0.0001f)
    }

    @Test
    fun `timelineXToMs inverts timelineMsToX`() {
        val durationMs = 8_000L
        val widthPx = 160f
        val positionMs = 2_000L
        val x = timelineMsToX(positionMs, durationMs, widthPx)
        assertEquals(positionMs, timelineXToMs(x, durationMs, widthPx))
    }

    @Test
    fun `top ruler and track lane share playhead x for same inputs`() {
        val durationMs = 20_000L
        val positionMs = 12_345L
        val waveformWidthPx = 176f

        val rulerX = timelineMsToX(positionMs, durationMs, waveformWidthPx)
        val laneX = timelineMsToX(positionMs, durationMs, waveformWidthPx)

        assertEquals(rulerX, laneX, 0.0001f)
        assertEquals(
            timelinePlayheadFraction(positionMs, durationMs),
            rulerX / waveformWidthPx,
            0.0001f,
        )
    }

    @Test
    fun `offset clip does not change global playhead x`() {
        val durationMs = 30_000L
        val positionMs = 15_000L
        val waveformWidthPx = 300f

        assertEquals(150f, timelineMsToX(positionMs, durationMs, waveformWidthPx), 0.0001f)
        assertEquals(
            timelinePlayheadFraction(positionMs, durationMs),
            0.5f,
            0.0001f,
        )
    }

    @Test
    fun `timeline duration change keeps ruler and lane x aligned`() {
        val positionMs = 4_000L
        val widthPx = 100f
        val durationMs = 20_000L

        val rulerX = timelineMsToX(positionMs, durationMs, widthPx)
        val laneX = timelineMsToX(positionMs, durationMs, widthPx)

        assertEquals(20f, rulerX, 0.0001f)
        assertEquals(rulerX, laneX, 0.0001f)
    }

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
        val waveformWidthPx = laneWidthPx * TimelineWaveformWidthFraction
        val timelineDurationMs = 10_000L

        assertEquals(
            1f,
            timelinePlayheadFractionFromWaveformX(waveformWidthPx, waveformWidthPx),
            0.0001f,
        )
        assertEquals(
            waveformWidthPx,
            timelineMsToX(
                timelinePlayheadPositionMs(1f, timelineDurationMs),
                timelineDurationMs,
                waveformWidthPx,
            ),
            0.0001f,
        )
    }

    @Test
    fun `base duration changes clamp playhead`() {
        assertEquals(3_000L, timelinePlayheadClampedPositionMs(7_500L, 3_000L))
        assertEquals(0L, timelinePlayheadClampedPositionMs(-500L, 3_000L))
    }
}
