package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
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
        val (start, end) =
            applyLoopRegionOutsideTap(
                pointerMs = 18_000L,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
            )

        assertEquals(6_000L, start)
        assertEquals(18_000L, end)
    }

    @Test
    fun `outside tap left of region moves left handle to pointer`() {
        val (start, end) =
            applyLoopRegionOutsideTap(
                pointerMs = 2_000L,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
            )

        assertEquals(2_000L, start)
        assertEquals(12_000L, end)
    }

    @Test
    fun `pointerXToSourceMs maps clip width to source duration`() {
        assertEquals(0L, pointerXToSourceMs(0f, 1_000f, 20_000L))
        assertEquals(10_000L, pointerXToSourceMs(500f, 1_000f, 20_000L))
        assertEquals(20_000L, pointerXToSourceMs(1_000f, 1_000f, 20_000L))
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
    fun `resolve drag mode maps outside pointer to jump modes`() {
        assertEquals(
            LoopRegionDragMode.JumpLeft,
            resolveLoopRegionDragMode(pointerMs = 1_000L, loopStartMs = 6_000L, loopEndMs = 12_000L),
        )
        assertEquals(
            LoopRegionDragMode.JumpRight,
            resolveLoopRegionDragMode(pointerMs = 15_000L, loopStartMs = 6_000L, loopEndMs = 12_000L),
        )
        assertEquals(
            LoopRegionDragMode.MoveRegion,
            resolveLoopRegionDragMode(pointerMs = 8_000L, loopStartMs = 6_000L, loopEndMs = 12_000L),
        )
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
    fun `begin drag outside left jumps left handle and locks right edge`() {
        val begin =
            beginLoopRegionDragAtPointer(
                pointerXInClipPx = 100f,
                clipWidthPx = 1_000f,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = 16f,
            )

        assertEquals(LoopRegionActiveDragMode.LeftHandle, begin.activeMode)
        assertEquals(2_000L, begin.loopStartMs)
        assertEquals(12_000L, begin.loopEndMs)
        assertEquals(12_000L, begin.anchorEndMs)
    }

    @Test
    fun `begin drag outside right jumps right handle and locks left edge`() {
        val begin =
            beginLoopRegionDragAtPointer(
                pointerXInClipPx = 700f,
                clipWidthPx = 1_000f,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = 16f,
            )

        assertEquals(LoopRegionActiveDragMode.RightHandle, begin.activeMode)
        assertEquals(6_000L, begin.loopStartMs)
        assertEquals(14_000L, begin.loopEndMs)
        assertEquals(6_000L, begin.anchorStartMs)
    }

    @Test
    fun `begin drag inside region selects move without changing bounds`() {
        val begin =
            beginLoopRegionDragAtPointer(
                pointerXInClipPx = 400f,
                clipWidthPx = 1_000f,
                loopStartMs = 6_000L,
                loopEndMs = 12_000L,
                sourceDurationMs = 20_000L,
                handleHitHalfWidthPx = 16f,
            )

        assertEquals(LoopRegionActiveDragMode.MoveRegion, begin.activeMode)
        assertEquals(6_000L, begin.loopStartMs)
        assertEquals(12_000L, begin.loopEndMs)
        assertEquals(8_000L, begin.moveOriginPointerMs)
    }

    @Test
    fun `active drag preserves fixed edge for left handle`() {
        val (start, end) =
            applyLoopRegionActiveDrag(
                activeMode = LoopRegionActiveDragMode.LeftHandle,
                pointerMs = 4_000L,
                anchorStartMs = 4_000L,
                anchorEndMs = 12_000L,
                moveOriginPointerMs = 0L,
                sourceDurationMs = 20_000L,
            )

        assertEquals(4_000L, start)
        assertEquals(12_000L, end)
    }

    @Test
    fun `active drag preserves fixed edge for right handle`() {
        val (start, end) =
            applyLoopRegionActiveDrag(
                activeMode = LoopRegionActiveDragMode.RightHandle,
                pointerMs = 15_000L,
                anchorStartMs = 6_000L,
                anchorEndMs = 15_000L,
                moveOriginPointerMs = 0L,
                sourceDurationMs = 20_000L,
            )

        assertEquals(6_000L, start)
        assertEquals(15_000L, end)
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
