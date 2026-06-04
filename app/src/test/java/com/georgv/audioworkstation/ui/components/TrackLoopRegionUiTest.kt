package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.core.track.applyLoopRegionLeftHandleDrag
import com.georgv.audioworkstation.core.track.applyLoopRegionMoveDrag
import com.georgv.audioworkstation.core.track.applyLoopRegionRightHandleDrag
import com.georgv.audioworkstation.core.track.beginLoopRegionDragAtAreaPointer
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
    fun `active recording uses live meter even when persisted peaks exist`() {
        assertEquals(
            TimelineLaneWaveformMode.LiveRecordingMeter,
            timelineLaneWaveformMode(
                waveformState = WaveformState.Ready(WaveformPeaks.Placeholder),
                importStatus = TrackImportStatus.READY,
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
                importStatus = TrackImportStatus.READY,
                isActiveRecording = true,
                recordingInputLevel = 0.25f,
            ),
        )
    }

    @Test
    fun `live recording meter used without input level snapshot`() {
        assertEquals(
            TimelineLaneWaveformMode.LiveRecordingMeter,
            timelineLaneWaveformMode(
                waveformState = WaveformState.NoWaveform,
                importStatus = TrackImportStatus.READY,
                isActiveRecording = true,
                recordingInputLevel = null,
            ),
        )
    }

    @Test
    fun `backing track during recording keeps persisted peaks mode`() {
        assertEquals(
            TimelineLaneWaveformMode.PersistedPeaks,
            timelineLaneWaveformMode(
                waveformState = WaveformState.Ready(WaveformPeaks.Placeholder),
                importStatus = TrackImportStatus.READY,
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
            TimelineLaneWaveformMode.LiveRecordingMeter,
            timelineLaneWaveformMode(
                waveformState = clip.waveformState,
                importStatus = TrackImportStatus.READY,
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
        val begin = beginLoopRegionDragAtClipX(pointerXInClipPx = 300f, handleHitHalfWidthPx = 48f)
        assertEquals(6_000L, begin.loopStartMs)
        val (start, _) =
            applyLoopRegionLeftHandleDrag(
                pointerMs = 5_000L,
                loopEndMs = begin.anchorEndMs,
                sourceDurationMs = 20_000L,
            )
        assertEquals(5_000L, start)
    }

    @Test
    fun `right handle drag preserves loop end on begin then updates on move`() {
        val begin = beginLoopRegionDragAtClipX(pointerXInClipPx = 600f, handleHitHalfWidthPx = 48f)
        assertEquals(12_000L, begin.loopEndMs)
        val (_, end) =
            applyLoopRegionRightHandleDrag(
                pointerMs = 15_000L,
                loopStartMs = begin.anchorStartMs,
                sourceDurationMs = 20_000L,
            )
        assertEquals(15_000L, end)
    }

    @Test
    fun `region drag moves whole loop region`() {
        val begin = beginLoopRegionDragAtClipX(pointerXInClipPx = 500f, handleHitHalfWidthPx = 48f)
        val (start, end) =
            applyLoopRegionMoveDrag(
                deltaMs = 12_000L - begin.moveOriginPointerMs,
                loopStartMs = begin.anchorStartMs,
                loopEndMs = begin.anchorEndMs,
                sourceDurationMs = 20_000L,
            )
        assertEquals(8_000L, start)
        assertEquals(14_000L, end)
    }

    @Test
    fun `outside right tap jumps right handle to pointer`() {
        val begin = beginLoopRegionDragAtClipX(pointerXInClipPx = 900f, handleHitHalfWidthPx = 48f)
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

    private fun beginLoopRegionDragAtClipX(
        pointerXInClipPx: Float,
        handleHitHalfWidthPx: Float,
    ): com.georgv.audioworkstation.core.track.LoopRegionDragBeginResult {
        val clipStartPx = 0f
        val clipWidthPx = 1_000f
        val loopStartMs = 6_000L
        val loopEndMs = 12_000L
        val sourceDurationMs = 20_000L
        return beginLoopRegionDragAtAreaPointer(
            areaXPx = clipStartPx + pointerXInClipPx,
            clipStartPx = clipStartPx,
            clipWidthPx = clipWidthPx,
            leftHandleAreaX = clipStartPx + clipWidthPx * (loopStartMs.toDouble() / sourceDurationMs.toDouble()).toFloat(),
            rightHandleAreaX = clipStartPx + clipWidthPx * (loopEndMs.toDouble() / sourceDurationMs.toDouble()).toFloat(),
            loopStartMs = loopStartMs,
            loopEndMs = loopEndMs,
            sourceDurationMs = sourceDurationMs,
            handleHitHalfWidthPx = handleHitHalfWidthPx,
        )
    }
}
