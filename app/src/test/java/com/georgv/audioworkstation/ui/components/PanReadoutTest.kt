package com.georgv.audioworkstation.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PanReadoutTest {
    @Test
    fun mapsCenterAndFullSides() {
        assertEquals("Ц", localizedPanReadout("C", "Л", "П", "Ц"))
        assertEquals("Л", localizedPanReadout("L", "Л", "П", "Ц"))
        assertEquals("П", localizedPanReadout("R", "Л", "П", "Ц"))
    }

    @Test
    fun keepsTheTenthsAndTranslatesTheSide() {
        assertEquals("0.4Л", localizedPanReadout("0.4L", "Л", "П", "Ц"))
        assertEquals("0.7П", localizedPanReadout("0.7R", "Л", "П", "Ц"))
    }
}
