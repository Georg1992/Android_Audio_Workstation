package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectTrackReorderTest {

    @Test
    fun `returns null when dragged center does not cross a neighbor`() {
        val target = computeNeighborSwapTarget(
            currentIndex = 1,
            listSize = 4,
            draggedCenterY = 150f,
            previousNeighborCenterY = 50f,
            nextNeighborCenterY = 250f,
        )

        assertNull(target)
    }

    @Test
    fun `moves down exactly one step when crossing next neighbor center`() {
        val target = computeNeighborSwapTarget(
            currentIndex = 1,
            listSize = 4,
            draggedCenterY = 251f,
            previousNeighborCenterY = 50f,
            nextNeighborCenterY = 250f,
        )

        assertEquals(2, target)
    }

    @Test
    fun `moves up exactly one step when crossing previous neighbor center`() {
        val target = computeNeighborSwapTarget(
            currentIndex = 2,
            listSize = 4,
            draggedCenterY = 49f,
            previousNeighborCenterY = 50f,
            nextNeighborCenterY = 250f,
        )

        assertEquals(1, target)
    }

    @Test
    fun `does not skip even when dragged far beyond next neighbor`() {
        val target = computeNeighborSwapTarget(
            currentIndex = 1,
            listSize = 4,
            draggedCenterY = 999f,
            previousNeighborCenterY = 50f,
            nextNeighborCenterY = 250f,
        )

        assertEquals(2, target)
    }

    @Test
    fun `moveTrack moves first item down one step`() {
        val reordered = moveTrack(testTracks(), fromIndex = 0, toIndex = 1)

        assertEquals(listOf("2", "1", "3", "4"), reordered.map { it.id })
    }

    @Test
    fun `moveTrack moves last item up one step`() {
        val reordered = moveTrack(testTracks(), fromIndex = 3, toIndex = 2)

        assertEquals(listOf("1", "2", "4", "3"), reordered.map { it.id })
    }

    @Test
    fun `repeated one step moves traverse multiple positions without skipping`() {
        var tracks = testTracks()

        val firstTarget = computeNeighborSwapTarget(
            currentIndex = 0,
            listSize = 4,
            draggedCenterY = 151f,
            previousNeighborCenterY = null,
            nextNeighborCenterY = 150f,
        )
        tracks = moveTrack(tracks, fromIndex = 0, toIndex = firstTarget!!)

        val secondTarget = computeNeighborSwapTarget(
            currentIndex = 1,
            listSize = 4,
            draggedCenterY = 251f,
            previousNeighborCenterY = 50f,
            nextNeighborCenterY = 250f,
        )
        tracks = moveTrack(tracks, fromIndex = 1, toIndex = secondTarget!!)

        assertEquals(listOf("2", "3", "1", "4"), tracks.map { it.id })
    }

    @Test
    fun `does not move down when next neighbor center is unavailable`() {
        val target = computeNeighborSwapTarget(
            currentIndex = 1,
            listSize = 4,
            draggedCenterY = 310f,
            previousNeighborCenterY = 120f,
            nextNeighborCenterY = null,
            currentItemTop = 200f,
            currentItemBottom = 300f,
        )

        assertNull(target)
    }

    @Test
    fun `does not move up when previous neighbor center is unavailable`() {
        val target = computeNeighborSwapTarget(
            currentIndex = 2,
            listSize = 4,
            draggedCenterY = 185f,
            previousNeighborCenterY = null,
            nextNeighborCenterY = 400f,
            currentItemTop = 200f,
            currentItemBottom = 280f,
        )

        assertNull(target)
    }

    @Test
    fun `moves down before next neighbor center when current bottom and neighbor center known`() {
        val frac = 0.38f
        val bottom = 220f
        val nextCenter = 280f
        val threshold = bottom + frac * (nextCenter - bottom)

        assertNull(
            computeNeighborSwapTarget(
                currentIndex = 1,
                listSize = 4,
                draggedCenterY = threshold - 1f,
                previousNeighborCenterY = 50f,
                nextNeighborCenterY = nextCenter,
                currentItemTop = 100f,
                currentItemBottom = bottom,
            )
        )
        assertEquals(
            2,
            computeNeighborSwapTarget(
                currentIndex = 1,
                listSize = 4,
                draggedCenterY = threshold + 1f,
                previousNeighborCenterY = 50f,
                nextNeighborCenterY = nextCenter,
                currentItemTop = 100f,
                currentItemBottom = bottom,
            )
        )
    }

    @Test
    fun `moves up before previous neighbor center when current top and neighbor center known`() {
        val frac = 0.38f
        val prevCenter = 100f
        val currentTop = 220f
        val threshold = prevCenter + frac * (currentTop - prevCenter)

        assertNull(
            computeNeighborSwapTarget(
                currentIndex = 2,
                listSize = 4,
                draggedCenterY = threshold + 1f,
                previousNeighborCenterY = prevCenter,
                nextNeighborCenterY = 400f,
                currentItemTop = currentTop,
                currentItemBottom = 300f,
            )
        )
        assertEquals(
            1,
            computeNeighborSwapTarget(
                currentIndex = 2,
                listSize = 4,
                draggedCenterY = threshold - 1f,
                previousNeighborCenterY = prevCenter,
                nextNeighborCenterY = 400f,
                currentItemTop = currentTop,
                currentItemBottom = 300f,
            )
        )
    }

    private fun testTracks(): List<TrackEntity> = listOf(
        track(id = "1", position = 0),
        track(id = "2", position = 1),
        track(id = "3", position = 2),
        track(id = "4", position = 3)
    )

    private fun track(id: String, position: Int) = TrackEntity(
        id = id,
        projectId = "project",
        name = "Track $id",
        position = position
    )
}
