package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveMixScopeTest {
    @Test
    fun `activeMixScopeTrackIds includes recording lane`() {
        assertEquals(
            setOf("a", "rec"),
            activeMixScopeTrackIds(setOf("a"), activeRecordingTrackId = "rec"),
        )
    }

    @Test
    fun `playbackMustStopAtScopeEnd when transport reaches session end`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                    importStatus = TrackImportStatus.READY,
                ),
            )
        assertFalse(playbackMustStopAtScopeEnd(9_999L, tracks))
        assertTrue(playbackMustStopAtScopeEnd(10_000L, tracks))
    }

    @Test
    fun `playheadMsAfterScopeStop clamps to session end or zero`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                    importStatus = TrackImportStatus.READY,
                ),
            )
        assertEquals(0L, playheadMsAfterScopeStop(5_000L, tracks, selectionEmpty = true))
        assertEquals(10_000L, playheadMsAfterScopeStop(12_000L, tracks, selectionEmpty = false))
    }
}
