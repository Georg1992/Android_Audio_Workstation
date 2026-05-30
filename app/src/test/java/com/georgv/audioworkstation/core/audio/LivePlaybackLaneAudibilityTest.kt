package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackLaneAudibilityTest {

    @Test
    fun `lane audibility follows selection for loaded session lanes`() {
        val lanes = arrayOf<String?>("a", "b", "c")
        assertArrayEquals(
            booleanArrayOf(true, true, false),
            laneAudibilityFromSelection(lanes, setOf("a", "b")),
        )
    }

    @Test
    fun `non-armed selection does not appear in lane audibility array`() {
        val lanes = arrayOf<String?>("a")
        val audibility = laneAudibilityFromSelection(lanes, setOf("a", "z"))
        org.junit.Assert.assertEquals(1, audibility.size)
        assertTrue(audibility[0])
    }

    @Test
    fun `deselecting loaded lane marks lane inaudible`() {
        val audibility =
            laneAudibilityFromSelection(arrayOf<String?>("a", "b"), setOf("b"))
        assertFalse(audibility[0])
        assertTrue(audibility[1])
    }
}
