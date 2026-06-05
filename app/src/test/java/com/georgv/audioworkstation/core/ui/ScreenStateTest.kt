package com.georgv.audioworkstation.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStateTest {

    @Test
    fun `isInitialLoad is true only when Pending and not refreshing`() {
        val pending = ScreenState(availability = DataAvailability.Pending, content = "x")
        assertTrue(pending.isInitialLoad)

        val pendingRefresh =
            ScreenState(
                availability = DataAvailability.Pending,
                content = "x",
                isRefreshing = true,
            )
        assertFalse(pendingRefresh.isInitialLoad)
    }

    @Test
    fun `isContentEmpty requires Ready and empty predicate`() {
        val pendingEmpty =
            ScreenState(availability = DataAvailability.Pending, content = emptyList<String>())
        assertFalse(pendingEmpty.isContentEmpty { it.isEmpty() })

        val readyEmpty =
            ScreenState(availability = DataAvailability.Ready, content = emptyList<String>())
        assertTrue(readyEmpty.isContentEmpty { it.isEmpty() })

        val readyWithItems =
            ScreenState(availability = DataAvailability.Ready, content = listOf("a"))
        assertFalse(readyWithItems.isContentEmpty { it.isEmpty() })
    }

    @Test
    fun `isStaleWhileRefreshing is true when Ready and refreshing`() {
        val state =
            ScreenState(
                availability = DataAvailability.Ready,
                content = listOf(1),
                isRefreshing = true,
            )
        assertTrue(state.isStaleWhileRefreshing)
    }
}
