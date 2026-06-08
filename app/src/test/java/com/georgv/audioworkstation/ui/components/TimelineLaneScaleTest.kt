package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.core.track.loopRegionOverlayFractions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineLaneScaleTest {

    private fun clip(
        durationMs: Long = 8_000L,
        startOffsetMs: Long = 0L,
        isLoop: Boolean = true,
    ) =
        TimelineClip(
            clipId = "a",
            laneId = "a",
            startOffsetMs = startOffsetMs,
            durationMs = durationMs,
            waveformState = WaveformState.NoWaveform,
            isTimelineBase = true,
            formattedDuration = "0:08",
            isLoop = isLoop,
        )

    @Test
    fun `timeline scale maps 8s source inside 15s lane to eight fifteenths width`() {
        val scale =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = false,
                laneLayoutDurationMs = 15_000L,
                clip = clip(durationMs = 8_000L),
            )

        assertEquals(TimelineLaneScaleMode.Timeline, scale.mode)
        assertEquals(0f, scale.clipStartFractionOnWaveformArea(), 0.0001f)
        assertEquals(8_000L / 15_000f, scale.clipWidthFractionOnWaveformArea(), 0.0001f)
    }

    @Test
    fun `source-fit scale maps full source to waveform area width`() {
        val scale =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = true,
                laneLayoutDurationMs = 15_000L,
                clip = clip(durationMs = 8_000L),
            )

        assertEquals(TimelineLaneScaleMode.SourceFitWhileEditing, scale.mode)
        assertEquals(0f, scale.clipStartFractionOnWaveformArea(), 0.0001f)
        assertEquals(1f, scale.clipWidthFractionOnWaveformArea(), 0.0001f)
    }

    @Test
    fun `x to source ms uses full clip width while source-fit editing`() {
        val sourceDurationMs = 8_000L
        val clipWidthPx = 1_000f

        assertEquals(
            4_000L,
            xInLaneClipToSourceMs(
                xPx = 500f,
                clipWidthPx = clipWidthPx,
                sourceDurationMs = sourceDurationMs,
            ),
        )
    }

    @Test
    fun `overlay fractions map loop bounds across source duration`() {
        val fractions =
            loopRegionOverlayFractions(
                loopStartMs = 2_000L,
                loopEndMs = 6_000L,
                sourceDurationMs = 8_000L,
            )

        assertEquals(0.25f, fractions.startFraction, 0.0001f)
        assertEquals(0.75f, fractions.endFraction, 0.0001f)
    }

    @Test
    fun `local playhead x uses source-fit clip width while editing`() {
        val sourceDurationMs = 8_000L
        val clipWidthPx = 1_000f

        assertEquals(
            500f,
            sourceMsToXInLaneClip(
                sourceMs = 4_000L,
                clipWidthPx = clipWidthPx,
                sourceDurationMs = sourceDurationMs,
            ),
            0.0001f,
        )
    }

    @Test
    fun `exiting edit mode returns timeline scale without changing source duration`() {
        val clip = clip(durationMs = 8_000L, isLoop = true)
        val editing =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = true,
                laneLayoutDurationMs = 15_000L,
                clip = clip,
            )
        val idle =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = false,
                laneLayoutDurationMs = 15_000L,
                clip = clip,
            )

        assertEquals(TimelineLaneScaleMode.SourceFitWhileEditing, editing.mode)
        assertEquals(TimelineLaneScaleMode.Timeline, idle.mode)
        assertEquals(editing.sourceDurationMs, idle.sourceDurationMs)
        assertEquals(editing.clipDurationMs, idle.clipDurationMs)
    }

    @Test
    fun `persistent viewport zoom uses source-fit without loop edit focus`() {
        assertTrue(timelineLaneUsesSourceFit(laneViewportZoomed = true, loopRegionEditFocus = false))
        assertFalse(timelineLaneUsesSourceFit(laneViewportZoomed = false, loopRegionEditFocus = false))
        assertTrue(timelineLaneUsesSourceFit(laneViewportZoomed = false, loopRegionEditFocus = true))

        val clip = clip(durationMs = 8_000L, startOffsetMs = 10_000L)
        val zoomedScale =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = true,
                laneLayoutDurationMs = 18_000L,
                clip = clip,
            )
        assertTrue(timelineLaneUsesSourceFit(laneViewportZoomed = true, loopRegionEditFocus = false))
        assertEquals(TimelineLaneScaleMode.SourceFitWhileEditing, zoomedScale.mode)
        assertEquals(1f, zoomedScale.clipWidthFractionOnWaveformArea(), 0.0001f)
    }

    @Test
    fun `source-fit expands offset clip to full waveform area`() {
        val scale =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = true,
                laneLayoutDurationMs = 18_000L,
                clip = clip(durationMs = 8_000L, startOffsetMs = 10_000L),
            )

        assertEquals(TimelineLaneScaleMode.SourceFitWhileEditing, scale.mode)
        assertEquals(0f, scale.clipStartFractionOnWaveformArea(), 0.0001f)
        assertEquals(1f, scale.clipWidthFractionOnWaveformArea(), 0.0001f)
    }

    @Test
    fun `pointer area maps full waveform width to source duration while source-fit`() {
        val sourceFit =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = true,
                laneLayoutDurationMs = 18_000L,
                clip = clip(durationMs = 8_000L, startOffsetMs = 10_000L),
            )
        val areaWidthPx = 900f

        assertEquals(
            8_000L,
            pointerAreaXToSourceMs(
                areaXPx = areaWidthPx,
                laneScale = sourceFit,
                waveformAreaWidthPx = areaWidthPx,
            ),
        )
        assertEquals(
            0L,
            pointerAreaXToSourceMs(
                areaXPx = 0f,
                laneScale = sourceFit,
                waveformAreaWidthPx = areaWidthPx,
            ),
        )
    }

    @Test
    fun `non-loop clip stays timeline scaled when edit focus inactive`() {
        val scale =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = false,
                laneLayoutDurationMs = 15_000L,
                clip = clip(durationMs = 8_000L, isLoop = false),
            )

        assertEquals(TimelineLaneScaleMode.Timeline, scale.mode)
    }

    @Test
    fun `playback idle does not enable source-fit`() {
        val scale =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = false,
                laneLayoutDurationMs = 15_000L,
                clip = clip(durationMs = 8_000L),
            )

        assertFalse(scale.mode == TimelineLaneScaleMode.SourceFitWhileEditing)
    }

    @Test
    fun `source-fit ruler uses source duration not lane layout`() {
        val scale =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = true,
                laneLayoutDurationMs = 15_000L,
                clip = clip(durationMs = 8_000L),
            )

        assertEquals(8_000L, scale.rulerDurationMs())
        assertEquals(1f, scale.rulerClipEndFraction(), 0.0001f)
    }

    @Test
    fun `timeline and source-fit area x differ for same source ms when clip is narrow`() {
        val timeline =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = false,
                laneLayoutDurationMs = 15_000L,
                clip = clip(durationMs = 8_000L),
            )
        val sourceFit =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = true,
                laneLayoutDurationMs = 15_000L,
                clip = clip(durationMs = 8_000L),
            )
        val areaWidthPx = 1_500f
        val loopStartMs = 2_000L

        val timelineX =
            sourceMsToPointerAreaX(
                sourceMs = loopStartMs,
                laneScale = timeline,
                waveformAreaWidthPx = areaWidthPx,
            )
        val sourceFitX =
            sourceMsToPointerAreaX(
                sourceMs = loopStartMs,
                laneScale = sourceFit,
                waveformAreaWidthPx = areaWidthPx,
            )

        assertEquals(200f, timelineX, 0.5f)
        assertEquals(375f, sourceFitX, 0.5f)
    }

    @Test
    fun `left handle snap uses source-fit pointer across full waveform area`() {
        val sourceFit =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = true,
                laneLayoutDurationMs = 18_000L,
                clip = clip(durationMs = 8_000L, startOffsetMs = 10_000L),
            )
        val areaWidthPx = 800f
        val fingerAreaX = 400f
        val pointerMs =
            pointerAreaXToSourceMs(
                areaXPx = fingerAreaX,
                laneScale = sourceFit,
                waveformAreaWidthPx = areaWidthPx,
            )
        val bounds =
            loopRegionOverlayAreaBounds(
                loopStartMs = pointerMs,
                loopEndMs = 6_000L,
                displayScale = sourceFit,
                waveformAreaWidthPx = areaWidthPx,
            )

        assertEquals(fingerAreaX, bounds.startPx, 1f)
    }

    @Test
    fun `source-fit ruler labels show timeline clip start and end`() {
        val labels =
            sourceFitRulerBoundaryLabels(
                formattedTimelineStart = "0:12",
                formattedTimelineEnd = "0:22",
            )

        assertEquals("0:12", labels.first().text)
        assertEquals(0f, labels.first().fraction, 0.0001f)
        assertEquals("0:22", labels.last().text)
        assertEquals(1f, labels.last().fraction, 0.0001f)
    }

    @Test
    fun `loop playback ruler labels span local loop timeline`() {
        val labels =
            loopPlaybackRulerBoundaryLabels(
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
            )

        assertEquals("0:00", labels.first().text)
        assertEquals(0f, labels.first().fraction, 0.0001f)
        assertEquals("0:07", labels.last().text)
        assertEquals(1f, labels.last().fraction, 0.0001f)
    }

    @Test
    fun `loop playback ruler duration matches loop region length`() {
        val scale =
            timelineLaneScaleForLoopPlayback(
                laneLayoutDurationMs = 30_000L,
                clip = clip(durationMs = 20_000L, startOffsetMs = 10_000L),
                loopStartMs = 2_000L,
                loopEndMs = 9_000L,
            )

        assertEquals(7_000L, scale.rulerDurationMs())
        assertEquals(0f, scale.clipStartFractionOnWaveformArea(), 0.0001f)
        assertEquals(1f, scale.clipWidthFractionOnWaveformArea(), 0.0001f)
    }

    @Test
    fun `offset clip keeps timeline placement until edit focus`() {
        val scale =
            timelineLaneScaleForLoopEdit(
                loopEditFocusActive = false,
                laneLayoutDurationMs = 20_000L,
                clip = clip(durationMs = 5_000L, startOffsetMs = 5_000L),
            )

        assertEquals(0.25f, scale.clipStartFractionOnWaveformArea(), 0.0001f)
        assertEquals(0.25f, scale.clipWidthFractionOnWaveformArea(), 0.0001f)
    }
}
