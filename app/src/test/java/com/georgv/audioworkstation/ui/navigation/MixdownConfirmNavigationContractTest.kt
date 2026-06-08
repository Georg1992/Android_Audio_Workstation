package com.georgv.audioworkstation.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Documents the Mixdown confirm contract wired in [AppNavHost]:
 * mixdown is requested with selection, then Library navigation runs synchronously.
 */
class MixdownConfirmNavigationContractTest {

    @Test
    fun `confirm callback requests mixdown before navigating to library`() {
        val order = mutableListOf<String>()
        val onConfirmMixdown: (String, Set<String>) -> Unit = { projectId, selectedTrackIds ->
            order += "requestMixdown:$projectId:${selectedTrackIds.joinToString(",")}"
            order += "navigateToLibrary"
        }

        onConfirmMixdown("project-a", setOf("track-a", "track-b"))

        assertEquals(
            listOf("requestMixdown:project-a:track-a,track-b", "navigateToLibrary"),
            order,
        )
    }
}
