package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackTimelineLaneTest {

    @Test
    fun `base duration uses longest visible clip duration`() {
        val clips = listOf(
            clip(id = "short", durationMs = 2_000L),
            clip(id = "long", durationMs = 30_000L)
        )

        assertEquals(30_000L, timelineBaseDurationMs(clips))
    }

    @Test
    fun `base duration is capped at project maximum`() {
        val clips = listOf(clip(durationMs = TimelineMaxDurationMs))

        assertEquals(TimelineMaxDurationMs, timelineBaseDurationMs(clips))
    }

    @Test
    fun `base duration has a safe minimum for empty content`() {
        assertEquals(TimelineMinimumBaseDurationMs, timelineBaseDurationMs(emptyList()))
    }

    @Test
    fun `longest track has full width fraction`() {
        val layout = timelineClipLayout(
            clip(durationMs = 30_000L),
            timelineBaseDurationMs = 30_000L
        )

        assertNotNull(layout)
        assertEquals(1f, layout?.widthFraction ?: 0f, 0.0001f)
    }

    @Test
    fun `shorter track width is relative to base duration`() {
        val layout = timelineClipLayout(
            clip(durationMs = 15_000L),
            timelineBaseDurationMs = 30_000L
        )

        assertNotNull(layout)
        assertEquals(0.5f, layout?.widthFraction ?: 0f, 0.0001f)
    }

    @Test
    fun `clip layout maps start and width to base fractions`() {
        val layout = timelineClipLayout(
            clip(startOffsetMs = 10_000L, durationMs = 20_000L),
            timelineBaseDurationMs = 100_000L
        )

        assertNotNull(layout)
        assertEquals(0.1f, layout?.startFraction ?: 0f, 0.0001f)
        assertEquals(0.2f, layout?.widthFraction ?: 0f, 0.0001f)
    }

    @Test
    fun `lane layout reserves proportional metadata column on the right`() {
        val layout = timelineLaneLayout(laneWidthDp = 200f)

        assertEquals(200f, layout.laneWidthDp, 0.0001f)
        assertEquals(200f * TimelineMetadataWidthFraction, layout.metadataWidthDp, 0.0001f)
        assertEquals(200f * TimelineWaveformWidthFraction, layout.waveformAreaWidthDp, 0.0001f)
    }

    @Test
    fun `lane layout scales metadata width for narrow lanes`() {
        val layout = timelineLaneLayout(laneWidthDp = 24f)

        assertEquals(24f * TimelineMetadataWidthFraction, layout.metadataWidthDp, 0.0001f)
        assertEquals(24f * TimelineWaveformWidthFraction, layout.waveformAreaWidthDp, 0.0001f)
    }

    @Test
    fun `shorter clip width is relative to waveform area not full lane`() {
        val lane = timelineLaneLayout(laneWidthDp = 200f)
        val clipLayout = timelineClipLayout(
            clip(durationMs = 15_000L),
            timelineBaseDurationMs = 30_000L
        )

        assertNotNull(clipLayout)
        assertEquals(0.5f, clipLayout?.widthFraction ?: 0f, 0.0001f)
        assertEquals(88f, lane.waveformAreaWidthDp * (clipLayout?.widthFraction ?: 0f), 0.5f)
    }

    @Test
    fun `clip layout ignores invalid durations`() {
        assertNull(timelineClipLayout(clip(durationMs = 0L), timelineBaseDurationMs = 10_000L))
        assertNull(timelineClipLayout(clip(durationMs = -1L), timelineBaseDurationMs = 10_000L))
    }

    @Test
    fun `duration formatting is compact`() {
        assertEquals("0:05", formatTimelineDuration(5_000L))
        assertEquals("1:23", formatTimelineDuration(83_000L))
        assertEquals("12:04", formatTimelineDuration(724_000L))
    }

    @Test
    fun `project timeline clips filter invalid track audio and preserve visible order`() {
        val tracks = listOf(
            TrackEntity(id = "a", projectId = "p", wavFilePath = "a.wav", duration = 1_000L),
            TrackEntity(id = "b", projectId = "p", wavFilePath = "", duration = 1_000L),
            TrackEntity(id = "c", projectId = "p", wavFilePath = "c.wav", duration = null),
            TrackEntity(id = "d", projectId = "p", wavFilePath = "d.wav", duration = 1_000L, isRecording = true),
            TrackEntity(id = "e", projectId = "p", wavFilePath = "e.wav", duration = 2_000L),
        )

        val clips = projectTimelineClips(tracks, waveformStatesByTrackId = emptyMap())

        assertEquals(listOf("a", "e"), clips.map { it.clipId })
    }

    @Test
    fun `project timeline clips use loading state before waveform is ready`() {
        val tracks = listOf(
            TrackEntity(id = "a", projectId = "p", wavFilePath = "a.wav", duration = 5_000L),
        )

        val clip = projectTimelineClips(tracks, waveformStatesByTrackId = emptyMap()).single()

        assertEquals(WaveformState.Loading, clip.waveformState)
        assertEquals("0:05", clip.formattedDuration)
    }

    @Test
    fun `project timeline clips preserve ready and failed states`() {
        val tracks = listOf(
            TrackEntity(id = "ready", projectId = "p", wavFilePath = "ready.wav", duration = 1_000L),
            TrackEntity(id = "failed", projectId = "p", wavFilePath = "failed.wav", duration = 1_000L),
        )

        val clips = projectTimelineClips(
            tracks = tracks,
            waveformStatesByTrackId = mapOf(
                "ready" to WaveformState.Ready(WaveformPeaks.Placeholder),
                "failed" to WaveformState.Failed,
            )
        )

        assertEquals(true, clips.first { it.clipId == "ready" }.waveformState is WaveformState.Ready)
        assertEquals(WaveformState.Failed, clips.first { it.clipId == "failed" }.waveformState)
    }

    @Test
    fun `timeline base calculation works before waveform is ready`() {
        val tracks = listOf(
            TrackEntity(id = "short", projectId = "p", wavFilePath = "short.wav", duration = 1_000L),
            TrackEntity(id = "long", projectId = "p", wavFilePath = "long.wav", duration = 4_000L),
        )

        val clips = projectTimelineClips(tracks, waveformStatesByTrackId = emptyMap())

        assertEquals(4_000L, timelineBaseDurationMs(clips))
        assertEquals(true, clips.first { it.clipId == "long" }.isTimelineBase)
    }

    @Test
    fun `project timeline clips detect base tracks`() {
        val tracks = listOf(
            TrackEntity(id = "short", projectId = "p", wavFilePath = "short.wav", duration = 1_000L),
            TrackEntity(id = "base", projectId = "p", wavFilePath = "base.wav", duration = 2_000L),
        )

        val clips = projectTimelineClips(tracks, waveformStatesByTrackId = emptyMap())

        assertEquals(false, clips.first { it.clipId == "short" }.isTimelineBase)
        assertEquals(true, clips.first { it.clipId == "base" }.isTimelineBase)
    }

    private fun clip(
        id: String = "clip",
        startOffsetMs: Long = 0L,
        durationMs: Long,
    ): TimelineClip =
        TimelineClip(
            clipId = id,
            laneId = id,
            startOffsetMs = startOffsetMs,
            durationMs = durationMs,
            waveformState = WaveformState.Ready(WaveformPeaks.Placeholder),
            isTimelineBase = false,
            formattedDuration = formatTimelineDuration(durationMs),
        )
}
