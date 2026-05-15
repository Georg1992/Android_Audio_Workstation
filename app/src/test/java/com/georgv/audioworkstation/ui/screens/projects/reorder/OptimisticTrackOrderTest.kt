package com.georgv.audioworkstation.ui.screens.projects.reorder

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OptimisticTrackOrderTest {

    @Test
    fun `same id order returns null`() {
        val live =
            listOf(
                track(id = "a", position = 0, gain = 10f),
                track(id = "b", position = 1, gain = 20f),
            )
        val proposed =
            listOf(
                track(id = "a", position = 99, gain = 999f),
                track(id = "b", position = 98, gain = 998f),
            )
        assertNull(OptimisticTrackOrder.applySession(live, proposed))
    }

    @Test
    fun `adjacent swap keeps live track data fields with updated positions`() {
        val a = track(id = "a", position = 0, gain = 1f)
        val b = track(id = "b", position = 1, gain = 2f)
        val c = track(id = "c", position = 2, gain = 3f)
        val live = listOf(a, b, c)
        val proposed = listOf(b.copy(position = 0), a.copy(position = 1), c.copy(position = 2))

        val result = OptimisticTrackOrder.applySession(live, proposed)
        assertNotNull(result)
        assertEquals(listOf("b", "a", "c"), result!!.map { it.id })
        assertEquals(listOf(0, 1, 2), result.map { it.position })
        assertEquals(2f, result[0].gain, 0f)
        assertEquals(1f, result[1].gain, 0f)
        assertEquals(3f, result[2].gain, 0f)
        assertSame(c, result[2])
    }

    @Test
    fun `trailing live tracks not in proposed prefix are appended with positions`() {
        val a = track(id = "a", position = 0)
        val b = track(id = "b", position = 1)
        val c = track(id = "c", position = 2)
        val live = listOf(a, b, c)
        val proposed = listOf(b, a)

        val result = OptimisticTrackOrder.applySession(live, proposed)
        assertNotNull(result)
        assertEquals(listOf("b", "a", "c"), result!!.map { it.id })
        assertEquals(listOf(0, 1, 2), result.map { it.position })
        assertEquals(c.id, result[2].id)
        assertEquals(c.gain, result[2].gain, 0f)
    }

    @Test
    fun `proposed ids missing from live are ignored`() {
        val a = track(id = "a", position = 0)
        val b = track(id = "b", position = 1)
        val c = track(id = "c", position = 2)
        val live = listOf(a, b, c)
        val phantom = track(id = "ghost", position = 0)
        val proposed = listOf(phantom, b, a)

        val result = OptimisticTrackOrder.applySession(live, proposed)
        assertNotNull(result)
        assertEquals(listOf("b", "a", "c"), result!!.map { it.id })
    }

    @Test
    fun `non-adjacent reorder merges by proposed id order`() {
        val a = track(id = "a", position = 0)
        val b = track(id = "b", position = 1)
        val c = track(id = "c", position = 2, gain = 7f)
        val live = listOf(a, b, c)
        val proposed = listOf(c, a, b)

        val result = OptimisticTrackOrder.applySession(live, proposed)
        assertNotNull(result)
        assertEquals(listOf("c", "a", "b"), result!!.map { it.id })
        assertEquals(listOf(0, 1, 2), result.map { it.position })
        assertEquals(7f, result[0].gain, 0f)
        assertEquals(100f, result[1].gain, 0f)
        assertEquals(100f, result[2].gain, 0f)
    }

    private fun track(id: String, position: Int, gain: Float = 100f) =
        TrackEntity(
            id = id,
            projectId = "p",
            name = id,
            position = position,
            gain = gain,
        )
}
