package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackLaneAudibilityTest {

    @Test
    fun `armed lane audibility follows selection for armed tracks only`() {
        val armed = listOf("a", "b", "c")
        assertArrayEquals(
            booleanArrayOf(true, true, false),
            armedLaneAudibilityFromSelection(armed, setOf("a", "b")),
        )
    }

    @Test
    fun `non-armed selection does not appear in lane audibility array`() {
        val armed = listOf("a")
        val audibility = armedLaneAudibilityFromSelection(armed, setOf("a", "z"))
        org.junit.Assert.assertEquals(1, audibility.size)
        assertTrue(audibility[0])
    }

    @Test
    fun `deselecting armed lane marks lane inaudible`() {
        val audibility =
            armedLaneAudibilityFromSelection(listOf("a", "b"), setOf("b"))
        assertFalse(audibility[0])
        assertTrue(audibility[1])
    }
}
