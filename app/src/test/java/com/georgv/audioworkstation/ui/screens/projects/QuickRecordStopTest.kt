package com.georgv.audioworkstation.ui.screens.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickRecordStopTest {
    @Test
    fun mixesTheFinishedTake() {
        assertEquals(
            setOf("track-1"),
            QuickRecordStop.tracksToMix(
                quickRecord = true,
                takeStopped = true,
                playableTrackIds = setOf("track-1"),
            ),
        )
    }

    @Test
    fun waitsUntilTheTakeHasStoppedAndHasAudio() {
        assertNull(
            QuickRecordStop.tracksToMix(
                quickRecord = true,
                takeStopped = false,
                playableTrackIds = setOf("track-1"),
            ),
        )
        assertNull(
            QuickRecordStop.tracksToMix(
                quickRecord = true,
                takeStopped = true,
                playableTrackIds = emptySet(),
            ),
        )
        assertNull(
            QuickRecordStop.tracksToMix(
                quickRecord = false,
                takeStopped = true,
                playableTrackIds = setOf("track-1"),
            ),
        )
    }
}
