package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLoopRegionTest {

    @Test
    fun `default loop region is zero to full duration when loop enabled`() {
        val track =
            track(
                duration = 20_000L,
                isLoop = true,
                loopStartMs = 0L,
                loopEndMs = null,
            )

        assertEquals(0L, track.effectiveLoopStartMs())
        assertEquals(20_000L, track.effectiveLoopEndMs())
    }

    @Test
    fun `loop disabled uses full duration regardless of stored region`() {
        val track =
            track(
                duration = 20_000L,
                isLoop = false,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
            )

        assertEquals(0L, track.effectiveLoopStartMs())
        assertEquals(20_000L, track.effectiveLoopEndMs())
    }

    @Test
    fun `disabling loop keeps persisted region values`() {
        val track =
            track(
                duration = 20_000L,
                isLoop = false,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
            )

        assertEquals(6_000L, track.loopStartMs)
        assertEquals(12_000L, track.loopEndMs)
    }

    @Test
    fun `re-enabling loop restores previous region`() {
        val track =
            track(
                duration = 20_000L,
                isLoop = true,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
            )

        assertEquals(6_000L, track.effectiveLoopStartMs())
        assertEquals(12_000L, track.effectiveLoopEndMs())
    }

    @Test
    fun `clampLoopRegionMs enforces minimum length and bounds`() {
        val (start, end) = clampLoopRegionMs(
            loopStartMs = 19_950L,
            loopEndMs = 20_000L,
            sourceDurationMs = 20_000L,
        )

        assertEquals(19_900L, start)
        assertEquals(20_000L, end)
    }

    @Test
    fun `clampLoopRegionMs rejects inverted bounds`() {
        val (start, end) = clampLoopRegionMs(
            loopStartMs = 15_000L,
            loopEndMs = 5_000L,
            sourceDurationMs = 20_000L,
        )

        assertTrue(end > start)
        assertTrue(end - start >= TrackLoopRegionMinLengthMs)
    }

    @Test
    fun `effectiveTimelineEnd uses full placement not loop end`() {
        val track =
            track(
                duration = 20_000L,
                timelineStartOffsetMs = 5_000L,
                isLoop = true,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
            )

        assertEquals(25_000L, track.effectiveTimelineEndMs())
    }

    @Test
    fun `overlay geometry maps ms to fractions`() {
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
    fun `left handle drag updates start and preserves end`() {
        val (start, end) =
            applyLoopRegionLeftHandleDrag(
                pointerMs = 4_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
            )

        assertEquals(4_000L, start)
        assertEquals(12_000L, end)
    }

    @Test
    fun `right handle drag updates end and preserves start`() {
        val (start, end) =
            applyLoopRegionRightHandleDrag(
                pointerMs = 15_000L,
                loopStartMs = 6_000L,
                sourceDurationMs = 20_000L,
            )

        assertEquals(6_000L, start)
        assertEquals(15_000L, end)
    }

    @Test
    fun `move drag preserves length and clamps to source duration`() {
        val (start, end) =
            applyLoopRegionMoveDrag(
                deltaMs = 5_000L,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
            )

        assertEquals(11_000L, start)
        assertEquals(17_000L, end)
    }

    @Test
    fun `outside tap right of region moves right handle to pointer`() {
        val begin = beginLoopRegionDragAtClipX(pointerXInClipPx = 900f)

        assertEquals(6_000L, begin.loopStartMs)
        assertEquals(18_000L, begin.loopEndMs)
    }

    @Test
    fun `outside tap left of region moves left handle to pointer`() {
        val begin = beginLoopRegionDragAtClipX(pointerXInClipPx = 100f)

        assertEquals(2_000L, begin.loopStartMs)
        assertEquals(12_000L, begin.loopEndMs)
    }

    @Test
    fun `pointerXToSourceMs maps clip width to source duration`() {
        assertEquals(0L, pointerXToSourceMs(0f, 1_000f, 20_000L))
        assertEquals(10_000L, pointerXToSourceMs(500f, 1_000f, 20_000L))
        assertEquals(20_000L, pointerXToSourceMs(1_000f, 1_000f, 20_000L))
    }

    @Test
    fun `loopRegionDisplayBoundsMs uses persisted props when idle`() {
        val (start, end) =
            loopRegionDisplayBoundsMs(
                isDragging = false,
                previewStartMs = 0L,
                previewEndMs = 20_000L,
                pendingCommitStartMs = null,
                pendingCommitEndMs = null,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
            )

        assertEquals(6_000L, start)
        assertEquals(12_000L, end)
    }

    @Test
    fun `loopRegionDisplayBoundsMs holds pending commit until props match`() {
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
        assertTrue(
            loopRegionPendingCommitResolved(
                loopStartMs = 7_000L,
                loopEndMs = 13_000L,
                pendingCommitStartMs = 7_000L,
                pendingCommitEndMs = 13_000L,
            ),
        )
    }

    @Test
    fun `loopRegionDisplayBoundsMs uses preview while dragging`() {
        val (start, end) =
            loopRegionDisplayBoundsMs(
                isDragging = true,
                previewStartMs = 8_000L,
                previewEndMs = 14_000L,
                pendingCommitStartMs = null,
                pendingCommitEndMs = null,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
            )

        assertEquals(8_000L, start)
        assertEquals(14_000L, end)
    }

    @Test
    fun `hasPersistedPlayableAudio requires wav path and duration`() {
        assertFalse(
            TrackEntity(id = "a", projectId = "p", wavFilePath = "", duration = 5_000L)
                .hasPersistedPlayableAudio(),
        )
        assertFalse(
            TrackEntity(id = "b", projectId = "p", wavFilePath = "b.wav", duration = null)
                .hasPersistedPlayableAudio(),
        )
        assertTrue(
            TrackEntity(id = "c", projectId = "p", wavFilePath = "c.wav", duration = 5_000L, isRecording = true)
                .hasPersistedPlayableAudio(),
        )
    }

    @Test
    fun `left handle drag enforces minimum length`() {
        val (start, end) =
            applyLoopRegionLeftHandleDrag(
                pointerMs = 19_950L,
                loopEndMs = 20_000L,
                sourceDurationMs = 20_000L,
            )

        assertEquals(19_900L, start)
        assertEquals(20_000L, end)
    }

    @Test
    fun `right handle drag enforces minimum length`() {
        val (start, end) =
            applyLoopRegionRightHandleDrag(
                pointerMs = 50L,
                loopStartMs = 0L,
                sourceDurationMs = 20_000L,
            )

        assertEquals(0L, start)
        assertEquals(100L, end)
    }

    @Test
    fun `resolve drag mode from pointer x selects jump left outside region`() {
        val clipWidthPx = 1_000f
        val handleHalfPx = 16f
        assertEquals(
            LoopRegionDragMode.JumpLeft,
            resolveLoopRegionDragModeFromPointerX(
                pointerXInClipPx = 100f,
                clipWidthPx = clipWidthPx,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = handleHalfPx,
            ),
        )
    }

    @Test
    fun `resolve drag mode from pointer x selects jump right outside region`() {
        val clipWidthPx = 1_000f
        val handleHalfPx = 16f
        assertEquals(
            LoopRegionDragMode.JumpRight,
            resolveLoopRegionDragModeFromPointerX(
                pointerXInClipPx = 700f,
                clipWidthPx = clipWidthPx,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = handleHalfPx,
            ),
        )
    }

    @Test
    fun `resolve drag mode from pointer x treats loop start edge as left handle`() {
        val clipWidthPx = 1_000f
        val handleHalfPx = 16f
        assertEquals(
            LoopRegionDragMode.LeftHandle,
            resolveLoopRegionDragModeFromPointerX(
                pointerXInClipPx = 10f,
                clipWidthPx = clipWidthPx,
                loopStartMs = 0L,
                loopEndMs = 20_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = handleHalfPx,
            ),
        )
    }

    @Test
    fun `resolve drag mode from pointer x treats loop end edge as right handle`() {
        val clipWidthPx = 1_000f
        val handleHalfPx = 16f
        assertEquals(
            LoopRegionDragMode.RightHandle,
            resolveLoopRegionDragModeFromPointerX(
                pointerXInClipPx = 990f,
                clipWidthPx = clipWidthPx,
                loopStartMs = 0L,
                loopEndMs = 20_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = handleHalfPx,
            ),
        )
    }

    @Test
    fun `resolve drag mode from pointer x maps center to move`() {
        val clipWidthPx = 1_000f
        val handleHalfPx = 16f
        assertEquals(
            LoopRegionDragMode.MoveRegion,
            resolveLoopRegionDragModeFromPointerX(
                pointerXInClipPx = 500f,
                clipWidthPx = clipWidthPx,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = handleHalfPx,
            ),
        )
    }

    @Test
    fun `resolve drag mode prefers move in center when handle bands overlap`() {
        val clipWidthPx = 1_000f
        val handleHalfPx = 48f
        assertEquals(
            LoopRegionDragMode.MoveRegion,
            resolveLoopRegionDragModeFromPointerX(
                pointerXInClipPx = 250f,
                clipWidthPx = clipWidthPx,
                loopStartMs = 4_800L,
                loopEndMs = 5_200L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = handleHalfPx,
            ),
        )
        assertEquals(
            LoopRegionDragMode.LeftHandle,
            resolveLoopRegionDragModeFromPointerX(
                pointerXInClipPx = 241f,
                clipWidthPx = clipWidthPx,
                loopStartMs = 4_800L,
                loopEndMs = 5_200L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = handleHalfPx,
            ),
        )
    }

    @Test
    fun `resolve drag mode from area pointer selects left handle near clip start`() {
        val clipStartPx = 120f
        val clipWidthPx = 800f
        val handleHalfPx = 24f
        assertEquals(
            LoopRegionDragMode.LeftHandle,
            resolveLoopRegionDragModeFromAreaPointer(
                areaXPx = clipStartPx - 8f,
                clipStartPx = clipStartPx,
                clipWidthPx = clipWidthPx,
                leftHandleAreaX = clipStartPx,
                rightHandleAreaX = clipStartPx + clipWidthPx,
                loopStartMs = 0L,
                loopEndMs = 20_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = handleHalfPx,
            ),
        )
    }

    @Test
    fun `resolve drag mode from area pointer selects right handle near clip end`() {
        val clipStartPx = 120f
        val clipWidthPx = 800f
        val handleHalfPx = 24f
        val clipEndPx = clipStartPx + clipWidthPx
        assertEquals(
            LoopRegionDragMode.RightHandle,
            resolveLoopRegionDragModeFromAreaPointer(
                areaXPx = clipEndPx + 8f,
                clipStartPx = clipStartPx,
                clipWidthPx = clipWidthPx,
                leftHandleAreaX = clipStartPx,
                rightHandleAreaX = clipEndPx,
                loopStartMs = 0L,
                loopEndMs = 20_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = handleHalfPx,
            ),
        )
    }

    @Test
    fun `begin drag outside left jumps left handle and locks right edge`() {
        val begin = beginLoopRegionDragAtClipX(pointerXInClipPx = 100f)

        assertEquals(LoopRegionActiveDragMode.LeftHandle, begin.activeMode)
        assertEquals(2_000L, begin.loopStartMs)
        assertEquals(12_000L, begin.loopEndMs)
        assertEquals(12_000L, begin.anchorEndMs)
    }

    @Test
    fun `begin drag outside right jumps right handle and locks left edge`() {
        val begin = beginLoopRegionDragAtClipX(pointerXInClipPx = 700f)

        assertEquals(LoopRegionActiveDragMode.RightHandle, begin.activeMode)
        assertEquals(6_000L, begin.loopStartMs)
        assertEquals(14_000L, begin.loopEndMs)
        assertEquals(6_000L, begin.anchorStartMs)
    }

    @Test
    fun `begin drag inside region selects move without changing bounds`() {
        val begin = beginLoopRegionDragAtClipX(pointerXInClipPx = 400f)

        assertEquals(LoopRegionActiveDragMode.MoveRegion, begin.activeMode)
        assertEquals(6_000L, begin.loopStartMs)
        assertEquals(12_000L, begin.loopEndMs)
        assertEquals(8_000L, begin.moveOriginPointerMs)
    }

    @Test
    fun `active drag preserves fixed edge for left handle`() {
        val (start, end) =
            applyLoopRegionLeftHandleDrag(
                pointerMs = 4_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
            )

        assertEquals(4_000L, start)
        assertEquals(12_000L, end)
    }

    @Test
    fun `active drag preserves fixed edge for right handle`() {
        val (start, end) =
            applyLoopRegionRightHandleDrag(
                pointerMs = 15_000L,
                loopStartMs = 6_000L,
                sourceDurationMs = 20_000L,
            )

        assertEquals(6_000L, start)
        assertEquals(15_000L, end)
    }

    private fun beginLoopRegionDragAtClipX(pointerXInClipPx: Float): LoopRegionDragBeginResult {
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
            handleHitHalfWidthPx = 16f,
        )
    }

    private fun track(
        duration: Long,
        isLoop: Boolean = false,
        loopStartMs: Long = 0L,
        loopEndMs: Long? = null,
        timelineStartOffsetMs: Long = 0L,
    ) = TrackEntity(
        id = "t",
        projectId = "p",
        wavFilePath = "t.wav",
        duration = duration,
        isLoop = isLoop,
        loopStartMs = loopStartMs,
        loopEndMs = loopEndMs,
        timelineStartOffsetMs = timelineStartOffsetMs,
    )
}
