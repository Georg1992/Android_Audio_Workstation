package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMixdownSelectionTest {

    @Test
    fun `selectedPlayableTracks preserves order and filters selection`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    position = 0,
                    wavFilePath = "a.wav",
                    duration = 1_000L,
                    importStatus = TrackImportStatus.READY,
                ),
                TrackEntity(
                    id = "b",
                    projectId = "p",
                    position = 1,
                    wavFilePath = "b.wav",
                    duration = 1_000L,
                    importStatus = TrackImportStatus.READY,
                ),
                TrackEntity(
                    id = "c",
                    projectId = "p",
                    position = 2,
                    wavFilePath = "",
                    duration = 1_000L,
                ),
            )

        assertEquals(
            listOf("b"),
            selectedPlayableTracks(tracks, setOf("b", "c")).map { it.id },
        )
    }
}
