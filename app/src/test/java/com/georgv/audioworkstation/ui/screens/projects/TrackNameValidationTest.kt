package com.georgv.audioworkstation.ui.screens.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackNameValidationTest {

    @Test
    fun `blank name is rejected`() {
        val result = validateTrackName("   ")

        assertTrue(result is TrackNameValidationResult.Invalid)
    }

    @Test
    fun `whitespace is normalized`() {
        val result = validateTrackName("  My   Track   Name  ")

        assertEquals(
            "My Track Name",
            (result as TrackNameValidationResult.Valid).normalizedName
        )
    }

    @Test
    fun `too long name is rejected`() {
        val result = validateTrackName("a".repeat(41))

        assertTrue(result is TrackNameValidationResult.Invalid)
    }
}
