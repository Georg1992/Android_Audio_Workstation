package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.core.track.applyLoopRegionActiveDrag
import com.georgv.audioworkstation.core.track.beginLoopRegionDragAtPointer
import com.georgv.audioworkstation.core.track.loopRegionDisplayBoundsMs
import com.georgv.audioworkstation.core.track.loopRegionOverlayFractions
import com.georgv.audioworkstation.core.track.trackSourcePlayheadMs
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLoopRegionUiTest {

    @Test
    fun `persisted peaks win over live recording meter`() {
        assertEquals(
            TimelineLaneWaveformMode.PersistedPeaks,
            timelineLaneWaveformMode(
                waveformState = WaveformState.Ready(WaveformPeaks.Placeholder),
                isActiveRecording = true,
                recordingInputLevel = 0.5f,
            ),
        )
    }

    @Test
    fun `brand-new recording without peaks uses live meter`() {
        assertEquals(
            TimelineLaneWaveformMode.LiveRecordingMeter,
            timelineLaneWaveformMode(
                waveformState = WaveformState.NoWaveform,
                isActiveRecording = true,
                recordingInputLevel = 0.25f,
            ),
        )
    }

    @Test
    fun `backing track during recording keeps persisted peaks mode`() {
        assertEquals(
            TimelineLaneWaveformMode.PersistedPeaks,
            timelineLaneWaveformMode(
                waveformState = WaveformState.Ready(WaveformPeaks.Placeholder),
                isActiveRecording = false,
                recordingInputLevel = null,
            ),
        )
    }

    @Test
    fun `punch target recording keeps loop overlay bounds in projection`() {
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
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId =
                    mapOf("target" to WaveformState.Ready(WaveformPeaks.Placeholder)),
                activeRecording =
                    ActiveRecordingTimelineClip(
                        trackId = "target",
                        startOffsetMs = 0L,
                        elapsedMs = 2_000L,
                    ),
                playheadPositionMs = 2_000L,
                extendVisibleTimelineForAllLoopedPlayback = false,
                extendVisibleTimelineForRecording = true,
            )

        val clip = projection.clipsByLaneId["target"]!!
        assertTrue(clip.isLoop)
        assertTrue(clip.isActiveRecording)
        assertEquals(6_000L, clip.effectiveStartMs)
        assertEquals(12_000L, clip.effectiveEndMs)
        assertTrue(clip.waveformState is WaveformState.Ready)
        assertEquals(
            TimelineLaneWaveformMode.PersistedPeaks,
            timelineLaneWaveformMode(
                waveformState = clip.waveformState,
                isActiveRecording = clip.isActiveRecording,
                recordingInputLevel = 0.4f,
            ),
        )
    }

    @Test
    fun `local loop playhead stays mapped during recording`() {
        val playheadMs =
            trackSourcePlayheadMs(
                globalPlayheadMs = 8_000L,
                timelineStartOffsetMs = 0L,
                sourceDurationMs = 20_000L,
                loopEnabled = true,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
            )

        assertEquals(8_000L, playheadMs)
        assertNotNull(
            loopRegionOverlayFractions(
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
            ),
        )
    }

    @Test
    fun `loop editing disabled during playback and recording`() {
        assertFalse(loopRegionEditingEnabled(playbackActive = true, recordingActive = false))
        assertFalse(loopRegionEditingEnabled(playbackActive = false, recordingActive = true))
        assertFalse(loopRegionEditingEnabled(playbackActive = true, recordingActive = true))
        assertTrue(loopRegionEditingEnabled(playbackActive = false, recordingActive = false))
    }

    @Test
    fun `left handle drag preserves loop start on begin then updates on move`() {
        val begin =
            beginLoopRegionDragAtPointer(
                pointerXInClipPx = 300f,
                clipWidthPx = 1_000f,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = 48f,
            )
        assertEquals(6_000L, begin.loopStartMs)
        val (start, _) =
            applyLoopRegionActiveDrag(
                activeMode = begin.activeMode,
                pointerMs = 5_000L,
                anchorStartMs = begin.anchorStartMs,
                anchorEndMs = begin.anchorEndMs,
                moveOriginPointerMs = begin.moveOriginPointerMs,
                sourceDurationMs = 20_000L,
            )
        assertEquals(5_000L, start)
    }

    @Test
    fun `right handle drag preserves loop end on begin then updates on move`() {
        val begin =
            beginLoopRegionDragAtPointer(
                pointerXInClipPx = 600f,
                clipWidthPx = 1_000f,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = 48f,
            )
        assertEquals(12_000L, begin.loopEndMs)
        val (_, end) =
            applyLoopRegionActiveDrag(
                activeMode = begin.activeMode,
                pointerMs = 15_000L,
                anchorStartMs = begin.anchorStartMs,
                anchorEndMs = begin.anchorEndMs,
                moveOriginPointerMs = begin.moveOriginPointerMs,
                sourceDurationMs = 20_000L,
            )
        assertEquals(15_000L, end)
    }

    @Test
    fun `region drag moves whole loop region`() {
        val begin =
            beginLoopRegionDragAtPointer(
                pointerXInClipPx = 500f,
                clipWidthPx = 1_000f,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = 48f,
            )
        val (start, end) =
            applyLoopRegionActiveDrag(
                activeMode = begin.activeMode,
                pointerMs = 12_000L,
                anchorStartMs = begin.anchorStartMs,
                anchorEndMs = begin.anchorEndMs,
                moveOriginPointerMs = begin.moveOriginPointerMs,
                sourceDurationMs = 20_000L,
            )
        assertEquals(8_000L, start)
        assertEquals(14_000L, end)
    }

    @Test
    fun `outside right tap jumps right handle to pointer`() {
        val begin =
            beginLoopRegionDragAtPointer(
                pointerXInClipPx = 900f,
                clipWidthPx = 1_000f,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = 48f,
            )
        assertEquals(18_000L, begin.loopEndMs)
        assertEquals(6_000L, begin.loopStartMs)
    }

    @Test
    fun `display bounds hold pending commit without snapping to old props`() {
        val (start, end) =
            loopRegionDisplayBoundsMs(
                isDragging = false,
                previewStartMs = 6_000L,
                previewEndMs = 12_000L,
                pendingCommitStartMs = 7_000L,
                pendingCommitEndMs = 13_000L,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
            )
        assertEquals(7_000L, start)
        assertEquals(13_000L, end)
    }

    @Test
    fun `playback projection keeps loop bounds for overlay`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 20_000L,
                    isLoop = true,
                    loopStartMs = 2_000L,
                    loopEndMs = 9_000L,
                ),
            )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                activeRecording = null,
                playheadPositionMs = 25_000L,
                extendVisibleTimelineForAllLoopedPlayback = true,
                extendVisibleTimelineForRecording = false,
            )

        val clip = projection.clipsByLaneId["loop"]!!
        assertEquals(2_000L, clip.effectiveStartMs)
        assertEquals(9_000L, clip.effectiveEndMs)
    }
}
