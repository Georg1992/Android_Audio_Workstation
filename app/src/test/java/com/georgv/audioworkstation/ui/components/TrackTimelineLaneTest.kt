package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.core.audio.waveform.WaveformPeaks
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTimelineLaneTest {

    @Test
    fun `base duration uses longest visible clip duration`() {
        val clips = listOf(
            clip(id = "short", durationMs = 2_000L),
            clip(id = "long", durationMs = 30_000L)
        )

        assertEquals(30_000L, timelineBaseDurationMsFromClips(clips))
    }

    @Test
    fun `base duration uses start offset plus duration`() {
        val clips = listOf(
            clip(id = "early", durationMs = 20_000L, startOffsetMs = 0L),
            clip(id = "late", durationMs = 5_000L, startOffsetMs = 30_000L),
        )

        assertEquals(35_000L, timelineBaseDurationMsFromClips(clips))
    }

    @Test
    fun `base duration is not capped at viewport maximum`() {
        val beyondViewport = TimelineMaxDurationMs + 60_000L
        val clips = listOf(clip(durationMs = beyondViewport))

        assertEquals(beyondViewport, timelineBaseDurationMsFromClips(clips))
    }

    @Test
    fun `base duration is zero for empty content`() {
        assertEquals(0L, timelineBaseDurationMsFromClips(emptyList()))
    }

    @Test
    fun `waveform peaks crop to clip when source file is longer`() {
        val peaks =
            WaveformPeaks(
                amplitudes = List(100) { 0.5f },
                sourceDurationMs = 10_000L,
            )

        val cropped = waveformPeaksForTimelineClip(peaks, clipDurationMs = 2_500L)

        assertEquals(25, cropped.amplitudes.size)
    }

    @Test
    fun `waveform peaks crop stereo channels together`() {
        val peaks =
            WaveformPeaks(
                amplitudes = emptyList(),
                leftAmplitudes = List(100) { 0.5f },
                rightAmplitudes = List(100) { 0.8f },
                sourceDurationMs = 10_000L,
            )

        val cropped = waveformPeaksForTimelineClip(peaks, clipDurationMs = 2_500L)

        assertEquals(25, cropped.leftAmplitudes?.size)
        assertEquals(25, cropped.rightAmplitudes?.size)
    }

    @Test
    fun `waveform peaks unchanged when clip spans full source`() {
        val peaks =
            WaveformPeaks(
                amplitudes = listOf(0.2f, 0.8f),
                sourceDurationMs = 5_000L,
            )

        val cropped = waveformPeaksForTimelineClip(peaks, clipDurationMs = 5_000L)

        assertEquals(peaks, cropped)
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

        assertEquals(listOf("a", "d", "e"), clips.map { it.clipId })
    }

    @Test
    fun `project timeline clips exclude brand new recording take without persisted audio`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "new-take",
                    projectId = "p",
                    wavFilePath = "",
                    duration = null,
                    isRecording = true,
                ),
            )

        assertTrue(projectTimelineClips(tracks, waveformStatesByTrackId = emptyMap()).isEmpty())
    }

    @Test
    fun `project timeline clips keep loop bounds for recording punch target`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "target",
                    projectId = "p",
                    wavFilePath = "target.wav",
                    duration = 20_000L,
                    isRecording = true,
                    isLoop = true,
                    loopStartMs = 6_000L,
                    loopEndMs = 12_000L,
                ),
            )

        val clip = projectTimelineClips(tracks, waveformStatesByTrackId = emptyMap()).single()

        assertEquals(true, clip.isLoop)
        assertEquals(6_000L, clip.effectiveStartMs)
        assertEquals(12_000L, clip.effectiveEndMs)
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

        assertEquals(4_000L, timelineBaseDurationMsFromClips(clips))
        assertEquals(true, clips.first { it.clipId == "long" }.isTimelineBase)
    }

    @Test
    fun `base track ruler shows clip start and clip end only`() {
        val clip = clip(id = "base", durationMs = 30_000L).copy(isTimelineBase = true)
        val layout = timelineClipLayout(clip, timelineBaseDurationMs = 30_000L)!!

        val labels = timelineRulerBoundaryLabels(clip, layout, laneLayoutDurationMs = 30_000L)

        assertEquals(2, labels.size)
        assertEquals("0:00", labels[0].text)
        assertEquals(0f, labels[0].fraction, 0.0001f)
        assertEquals("0:30", labels[1].text)
        assertEquals(1f, labels[1].fraction, 0.0001f)
    }

    @Test
    fun `shorter track ruler shows clip start end and mix scope marker when lane is longer`() {
        val clip = clip(id = "short", durationMs = 15_000L)
        val layout = timelineClipLayout(clip, timelineBaseDurationMs = 60_000L)!!

        val labels =
            timelineRulerBoundaryLabels(
                clip = clip,
                layout = layout,
                laneLayoutDurationMs = 60_000L,
                globalMixScopeDurationMs = 30_000L,
            )

        assertEquals(3, labels.size)
        assertEquals("0:00", labels[0].text)
        assertEquals(0f, labels[0].fraction, 0.0001f)
        assertEquals("0:15", labels[1].text)
        assertEquals(0.25f, labels[1].fraction, 0.0001f)
        assertEquals("0:30", labels[2].text)
        assertEquals(0.5f, labels[2].fraction, 0.0001f)
    }

    @Test
    fun `offset clip ruler uses clip start and end times`() {
        val clip = clip(startOffsetMs = 10_000L, durationMs = 20_000L)
        val layout = timelineClipLayout(clip, timelineBaseDurationMs = 100_000L)!!

        val labels = timelineRulerBoundaryLabels(clip, layout, laneLayoutDurationMs = 100_000L)

        assertEquals("0:10", labels[0].text)
        assertEquals(0.1f, labels[0].fraction, 0.0001f)
        assertEquals("0:30", labels[1].text)
        assertEquals(0.3f, labels[1].fraction, 0.0001f)
        assertEquals(2, labels.size)
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

    @Test
    fun `project timeline clips use track timelineStartOffsetMs`() {
        val tracks = listOf(
            TrackEntity(
                id = "offset",
                projectId = "p",
                wavFilePath = "offset.wav",
                duration = 5_000L,
                timelineStartOffsetMs = 30_000L,
            ),
        )

        val clip = projectTimelineClips(tracks, waveformStatesByTrackId = emptyMap()).single()

        assertEquals(30_000L, clip.startOffsetMs)
    }

    @Test
    fun `existing tracks default timelineStartOffsetMs to zero in projection`() {
        val tracks = listOf(
            TrackEntity(id = "legacy", projectId = "p", wavFilePath = "legacy.wav", duration = 3_000L),
        )

        val clip = projectTimelineClips(tracks, waveformStatesByTrackId = emptyMap()).single()

        assertEquals(0L, clip.startOffsetMs)
    }

    @Test
    fun `looped track base uses full placement duration`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 20_000L,
                    isLoop = true,
                    loopEndMs = 12_000L,
                ),
                TrackEntity(id = "base", projectId = "p", wavFilePath = "base.wav", duration = 15_000L),
            )

        val clips = projectTimelineClips(tracks, waveformStatesByTrackId = emptyMap())

        assertEquals(20_000L, timelineBaseDurationMsFromClips(clips))
        assertEquals(true, clips.first { it.clipId == "loop" }.isTimelineBase)
        assertEquals(false, clips.first { it.clipId == "base" }.isTimelineBase)
        assertEquals(12_000L, clips.first { it.clipId == "loop" }.effectiveEndMs)
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
            effectiveStartMs = 0L,
            effectiveEndMs = durationMs,
        )
}
