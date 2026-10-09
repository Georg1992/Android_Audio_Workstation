package com.georgv.audioworkstation.ui.screens.community

import org.junit.Assert.assertEquals
import org.junit.Test

class CommunityDestinationTest {
    @Test
    fun staysPendingUntilTheSessionIsKnown() {
        assertEquals(CommunityDestination.Pending, communityDestination(sessionKnown = false, signedIn = false))
        assertEquals(CommunityDestination.Pending, communityDestination(sessionKnown = false, signedIn = true))
    }

    @Test
    fun opensTheCommunityHomeAfterSignIn() {
        assertEquals(CommunityDestination.Home, communityDestination(sessionKnown = true, signedIn = true))
    }

    @Test
    fun staysOnSignInWhenThereIsNoSession() {
        assertEquals(CommunityDestination.SignIn, communityDestination(sessionKnown = true, signedIn = false))
    }
}
