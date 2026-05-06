package com.georgv.audioworkstation.ui.layout

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackListPagingTest {

    @Test
    fun `six tracks page size four yields slices four then two`() {
        assertEquals(0, pageStartIndex(0, 4))
        assertEquals(4, pageEndExclusive(6, 0, 4))
        assertEquals(4, pageStartIndex(1, 4))
        assertEquals(6, pageEndExclusive(6, 1, 4))
        assertEquals(2, pageCount(6, 4))
    }

    @Test
    fun `boundary down swaps adjacent across page boundary`() {
        val tracks =
            ids("2", "3", "4", "1", "5", "6", "7")
        val swapped = swapAdjacentAtBoundaryDown(tracks, globalDragIndex = 3)
        requireNotNull(swapped)
        assertEquals(listOf("2", "3", "4", "5", "1", "6", "7"), swapped.map { it.id })
    }

    @Test
    fun `boundary down requires global index strictly before last element`() {
        val tracks = ids("a", "b")
        assertNull(swapAdjacentAtBoundaryDown(tracks, 1))
    }

    @Test
    fun `boundary up swaps with previous index`() {
        val tracks = ids("2", "3", "4", "5", "1", "6", "7")
        val swapped = swapAdjacentAtBoundaryUp(tracks, globalDragIndex = 4)
        requireNotNull(swapped)
        assertEquals(listOf("2", "3", "4", "1", "5", "6", "7"), swapped.map { it.id })
    }

    private fun ids(vararg id: String) =
        id.mapIndexed { idx, s ->
            TrackEntity(id = s, projectId = "p", name = null, position = idx)
        }
}
