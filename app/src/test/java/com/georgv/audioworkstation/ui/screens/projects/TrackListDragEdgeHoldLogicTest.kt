package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.ui.geometry.Rect
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackListDragEdgeHoldLogicTest {

    @Test
    fun `bottom band arms only on last index of page when more tracks below`() {
        val zone =
            computeEdgeHoldCandidateZone(
                fingerYRoot = LIST_BOTTOM - EDGE_BAND_PX + 1f,
                listBounds = LIST_BOUNDS,
                edgeBandPx = EDGE_BAND_PX,
                globalIndex = 3,
                listSize = 6,
                currentPageIndex = 0,
                pageSize = PAGE_SIZE,
            )

        assertEquals(EdgeHoldZone.Bottom, zone)
    }

    @Test
    fun `top band arms only on first index of page when previous page exists`() {
        val zone =
            computeEdgeHoldCandidateZone(
                fingerYRoot = LIST_TOP + EDGE_BAND_PX - 1f,
                listBounds = LIST_BOUNDS,
                edgeBandPx = EDGE_BAND_PX,
                globalIndex = 4,
                listSize = 6,
                currentPageIndex = 1,
                pageSize = PAGE_SIZE,
            )

        assertEquals(EdgeHoldZone.Top, zone)
    }

    @Test
    fun `edge band ignored for middle index on page`() {
        val zone =
            computeEdgeHoldCandidateZone(
                fingerYRoot = LIST_BOTTOM - EDGE_BAND_PX + 1f,
                listBounds = LIST_BOUNDS,
                edgeBandPx = EDGE_BAND_PX,
                globalIndex = 1,
                listSize = 6,
                currentPageIndex = 0,
                pageSize = PAGE_SIZE,
            )

        assertEquals(EdgeHoldZone.None, zone)
    }

    @Test
    fun `bottom band does not arm on final track of final page`() {
        val zone =
            computeEdgeHoldCandidateZone(
                fingerYRoot = LIST_BOTTOM - EDGE_BAND_PX + 1f,
                listBounds = LIST_BOUNDS,
                edgeBandPx = EDGE_BAND_PX,
                globalIndex = 5,
                listSize = 6,
                currentPageIndex = 1,
                pageSize = PAGE_SIZE,
            )

        assertEquals(EdgeHoldZone.None, zone)
    }

    @Test
    fun `edgeHoldScrollToPage advances and retreats within page bounds`() {
        assertEquals(1, edgeHoldScrollToPage(EdgeHoldZone.Bottom, 0, 6, PAGE_SIZE))
        assertEquals(0, edgeHoldScrollToPage(EdgeHoldZone.Top, 1, 6, PAGE_SIZE))
        assertEquals(1, edgeHoldScrollToPage(EdgeHoldZone.Bottom, 1, 6, PAGE_SIZE))
        assertNull(edgeHoldScrollToPage(EdgeHoldZone.None, 0, 6, PAGE_SIZE))
    }

    @Test
    fun `edgeHoldProgressBanner reflects held fraction`() {
        val banner = edgeHoldProgressBanner(EdgeHoldZone.Bottom, heldMs = 425L, pageEdgeHoldMs = PAGE_EDGE_HOLD_MS)

        assertTrue(banner is EdgeHoldBanner.Bottom)
        assertEquals(0.5f, (banner as EdgeHoldBanner.Bottom).progress, 0.001f)
    }

    @Test
    fun `reduceEdgeHoldOnCollect inactive when not dragging`() {
        val result =
            reduceEdgeHoldOnCollect(
                machine = EdgeHoldMachineState(),
                fingerYRoot = LIST_BOTTOM,
                draggingKey = null,
                tracks = tracks(6),
                globalIndex = 3,
                listBounds = LIST_BOUNDS,
                edgeBandPx = EDGE_BAND_PX,
                currentPageIndex = 0,
                pageSize = PAGE_SIZE,
                nowUptimeMs = 1_000L,
                pageEdgeHoldMs = PAGE_EDGE_HOLD_MS,
            )

        assertEquals(EdgeHoldBanner.None, result.banner)
        assertNull(result.pageScroll)
        assertEquals(EdgeHoldZone.None, result.machine.armedZone)
    }

    @Test
    fun `reduceEdgeHoldOnCollect shows progress before hold threshold`() {
        val enterMs = 1_000L
        val armed =
            reduceEdgeHoldOnCollect(
                machine = EdgeHoldMachineState(),
                fingerYRoot = fingerInBottomBand(),
                draggingKey = "4",
                tracks = tracks(6),
                globalIndex = 3,
                listBounds = LIST_BOUNDS,
                edgeBandPx = EDGE_BAND_PX,
                currentPageIndex = 0,
                pageSize = PAGE_SIZE,
                nowUptimeMs = enterMs,
                pageEdgeHoldMs = PAGE_EDGE_HOLD_MS,
            )
        val midHold =
            reduceEdgeHoldOnCollect(
                machine = armed.machine,
                fingerYRoot = fingerInBottomBand(),
                draggingKey = "4",
                tracks = tracks(6),
                globalIndex = 3,
                listBounds = LIST_BOUNDS,
                edgeBandPx = EDGE_BAND_PX,
                currentPageIndex = 0,
                pageSize = PAGE_SIZE,
                nowUptimeMs = enterMs + 400L,
                pageEdgeHoldMs = PAGE_EDGE_HOLD_MS,
            )

        assertTrue(midHold.banner is EdgeHoldBanner.Bottom)
        assertNull(midHold.pageScroll)
        assertEquals(EdgeHoldZone.Bottom, midHold.machine.armedZone)
    }

    @Test
    fun `reduceEdgeHoldOnCollect scrolls page after hold threshold without reorder`() {
        val enterMs = 2_000L
        val armed =
            reduceEdgeHoldOnCollect(
                machine = EdgeHoldMachineState(),
                fingerYRoot = fingerInBottomBand(),
                draggingKey = "4",
                tracks = tracks(6),
                globalIndex = 3,
                listBounds = LIST_BOUNDS,
                edgeBandPx = EDGE_BAND_PX,
                currentPageIndex = 0,
                pageSize = PAGE_SIZE,
                nowUptimeMs = enterMs,
                pageEdgeHoldMs = PAGE_EDGE_HOLD_MS,
            )
        val completed =
            reduceEdgeHoldOnCollect(
                machine = armed.machine,
                fingerYRoot = fingerInBottomBand(),
                draggingKey = "4",
                tracks = tracks(6),
                globalIndex = 3,
                listBounds = LIST_BOUNDS,
                edgeBandPx = EDGE_BAND_PX,
                currentPageIndex = 0,
                pageSize = PAGE_SIZE,
                nowUptimeMs = enterMs + PAGE_EDGE_HOLD_MS,
                pageEdgeHoldMs = PAGE_EDGE_HOLD_MS,
            )

        assertEquals(EdgeHoldBanner.None, completed.banner)
        assertEquals(1, completed.pageScroll)
        assertEquals(EdgeHoldZone.None, completed.machine.armedZone)
    }

    @Test
    fun `reduceEdgeHoldOnCollect resets when finger leaves edge band`() {
        val enterMs = 3_000L
        val armed =
            reduceEdgeHoldOnCollect(
                machine = EdgeHoldMachineState(),
                fingerYRoot = fingerInBottomBand(),
                draggingKey = "4",
                tracks = tracks(6),
                globalIndex = 3,
                listBounds = LIST_BOUNDS,
                edgeBandPx = EDGE_BAND_PX,
                currentPageIndex = 0,
                pageSize = PAGE_SIZE,
                nowUptimeMs = enterMs,
                pageEdgeHoldMs = PAGE_EDGE_HOLD_MS,
            )
        val leftBand =
            reduceEdgeHoldOnCollect(
                machine = armed.machine,
                fingerYRoot = LIST_TOP + LIST_HEIGHT / 2f,
                draggingKey = "4",
                tracks = tracks(6),
                globalIndex = 3,
                listBounds = LIST_BOUNDS,
                edgeBandPx = EDGE_BAND_PX,
                currentPageIndex = 0,
                pageSize = PAGE_SIZE,
                nowUptimeMs = enterMs + 200L,
                pageEdgeHoldMs = PAGE_EDGE_HOLD_MS,
            )

        assertEquals(EdgeHoldBanner.None, leftBand.banner)
        assertNull(leftBand.pageScroll)
        assertEquals(EdgeHoldZone.None, leftBand.machine.armedZone)
    }

    @Test
    fun `reduceEdgeHoldOnCollect top hold scrolls to previous page`() {
        val enterMs = 4_000L
        val armed =
            reduceEdgeHoldOnCollect(
                machine = EdgeHoldMachineState(),
                fingerYRoot = fingerInTopBand(),
                draggingKey = "5",
                tracks = tracks(6),
                globalIndex = 4,
                listBounds = LIST_BOUNDS,
                edgeBandPx = EDGE_BAND_PX,
                currentPageIndex = 1,
                pageSize = PAGE_SIZE,
                nowUptimeMs = enterMs,
                pageEdgeHoldMs = PAGE_EDGE_HOLD_MS,
            )
        val completed =
            reduceEdgeHoldOnCollect(
                machine = armed.machine,
                fingerYRoot = fingerInTopBand(),
                draggingKey = "5",
                tracks = tracks(6),
                globalIndex = 4,
                listBounds = LIST_BOUNDS,
                edgeBandPx = EDGE_BAND_PX,
                currentPageIndex = 1,
                pageSize = PAGE_SIZE,
                nowUptimeMs = enterMs + PAGE_EDGE_HOLD_MS,
                pageEdgeHoldMs = PAGE_EDGE_HOLD_MS,
            )

        assertEquals(0, completed.pageScroll)
    }

    private fun fingerInBottomBand(): Float = LIST_BOTTOM - EDGE_BAND_PX + 1f

    private fun fingerInTopBand(): Float = LIST_TOP + EDGE_BAND_PX - 1f

    private fun tracks(count: Int): List<TrackEntity> =
        (1..count).map { i ->
            TrackEntity(id = "$i", projectId = "p", name = null, position = i - 1)
        }

    private companion object {
        const val PAGE_SIZE = 4
        const val PAGE_EDGE_HOLD_MS = 850L
        const val EDGE_BAND_PX = 50f
        const val LIST_TOP = 100f
        const val LIST_BOTTOM = 500f
        const val LIST_HEIGHT = LIST_BOTTOM - LIST_TOP
        val LIST_BOUNDS = Rect(0f, LIST_TOP, 400f, LIST_BOTTOM)
    }
}
