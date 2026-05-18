package com.georgv.audioworkstation.ui.components

import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.theme.Dimens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineRulerTest {

    @Test
    fun `short timeline uses dense major and minor spacing`() {
        val intervals = timelineRulerIntervals(8_000L)

        assertEquals(1_000L, intervals?.minorIntervalMs)
        assertEquals(2_000L, intervals?.majorIntervalMs)
    }

    @Test
    fun `medium timeline uses default five and thirty second spacing`() {
        val intervals = timelineRulerIntervals(120_000L)

        assertEquals(5_000L, intervals?.minorIntervalMs)
        assertEquals(30_000L, intervals?.majorIntervalMs)
    }

    @Test
    fun `long timeline avoids excessive minor ticks`() {
        val intervals = timelineRulerIntervals(TimelineMaxDurationMs)

        assertTrue((TimelineMaxDurationMs / (intervals?.minorIntervalMs ?: 1L)) <= 60L)
    }

    @Test
    fun `tick count is reasonable for short medium and long timelines`() {
        val shortTicks = buildTimelineRulerTicks(8_000L)
        val mediumTicks = buildTimelineRulerTicks(120_000L)
        val longTicks = buildTimelineRulerTicks(TimelineMaxDurationMs)

        assertEquals(9, shortTicks.size)
        assertEquals(25, mediumTicks.size)
        assertTrue(longTicks.size in 20..80)
    }

    @Test
    fun `major ticks are flagged without labels`() {
        val ticks = buildTimelineRulerTicks(90_000L)

        assertEquals(4, ticks.count { it.isMajor })
        assertTrue(ticks.first { it.timeMs == 30_000L }.isMajor)
        assertFalse(ticks.first { it.timeMs == 5_000L }.isMajor)
    }

    @Test
    fun `minor ticks are generated between majors`() {
        val ticks = buildTimelineRulerTicks(90_000L)

        assertEquals(19, ticks.size)
        assertEquals(4, ticks.count { it.isMajor })
        assertEquals(15, ticks.count { !it.isMajor })
        assertEquals(5_000L, ticks[1].timeMs)
        assertFalse(ticks[1].isMajor)
    }

    @Test
    fun `tick fractions align with timeline clip scaling`() {
        val ticks = buildTimelineRulerTicks(30_000L)

        assertEquals(0f, ticks.first().positionFraction, 0.0001f)
        assertEquals(0.5f, ticks.first { it.timeMs == 15_000L }.positionFraction, 0.0001f)
        assertEquals(1f, ticks.last().positionFraction, 0.0001f)
    }

    @Test
    fun `invalid duration returns no ticks`() {
        assertTrue(buildTimelineRulerTicks(0L).isEmpty())
    }

    @Test
    fun `start label is inset past lane corner radius`() {
        assertEquals(
            timelineRulerLabelStartInset(),
            rulerClipLabelOffsetX(0f, 120.dp, alignToEnd = false),
        )
        assertEquals(Dimens.MediumRadius + Dimens.Stroke, timelineRulerLabelStartInset())
    }

    @Test
    fun `scrubber labels keep first and last major ticks`() {
        val ticks = buildTimelineRulerTicks(120_000L)
        val labeled = scrubberMajorTicksForLabels(ticks, minLabelSpacingFraction = 0.11f)

        assertEquals(0L, labeled.first().timeMs)
        assertEquals(120_000L, labeled.last().timeMs)
        assertTrue(labeled.size >= 3)
        assertTrue(labeled.all { it.isMajor })
    }

    @Test
    fun `scrubber labels thin crowded majors on long timelines`() {
        val ticks = buildTimelineRulerTicks(TimelineMaxDurationMs)
        val labeled = scrubberMajorTicksForLabels(ticks, minLabelSpacingFraction = 0.11f)

        assertTrue(labeled.size < ticks.count { it.isMajor })
        assertEquals(0L, labeled.first().timeMs)
        assertEquals(TimelineMaxDurationMs, labeled.last().timeMs)
    }

    @Test
    fun `clip end fraction matches clip layout`() {
        val layout = timelineClipLayout(
            clip(startOffsetMs = 10_000L, durationMs = 20_000L),
            timelineBaseDurationMs = 100_000L,
        )

        assertEquals(0.3f, timelineClipEndFraction(layout!!), 0.0001f)
    }

    private fun clip(
        startOffsetMs: Long = 0L,
        durationMs: Long,
    ): TimelineClip =
        TimelineClip(
            clipId = "clip",
            laneId = "clip",
            startOffsetMs = startOffsetMs,
            durationMs = durationMs,
            waveformState = WaveformState.Ready(WaveformPeaks.Placeholder),
            isTimelineBase = false,
            formattedDuration = formatTimelineDuration(durationMs),
        )
}
