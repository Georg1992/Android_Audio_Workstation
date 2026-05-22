package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionSemanticsTest {

    @Test
    fun `audibleTrackIds is selected intersect session lanes`() {
        val lanes = arrayOf<String?>("a", "b", null, "c")
        assertEquals(setOf("a", "b"), audibleTrackIds(setOf("a", "b", "d"), lanes))
    }

    @Test
    fun `audibleTrackIds empty when selection empty`() {
        val lanes = arrayOf<String?>("a", "b")
        assertEquals(emptySet<String>(), audibleTrackIds(emptySet(), lanes))
    }

    @Test
    fun `isTrackLoadedInSessionLane true when track mapped`() {
        val lanes = arrayOf<String?>("a", null, "b")
        assertTrue(isTrackLoadedInSessionLane("b", lanes))
        assertFalse(isTrackLoadedInSessionLane("c", lanes))
    }
}
