package com.georgv.audioworkstation.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginRouteTest {
    @Test
    fun opensTheLoginPageFromAnyOtherScreen() {
        assertTrue(LoginRoute.opensFrom(Routes.MAIN_MENU))
        assertTrue(LoginRoute.opensFrom(Routes.LIBRARY))
        assertTrue(LoginRoute.opensFrom(Routes.DEVICES))
        assertTrue(LoginRoute.opensFrom(Routes.CREATE_PROJECT))
        assertTrue(LoginRoute.opensFrom(Routes.COMMUNITY_HOME))
        assertTrue(LoginRoute.opensFrom(Routes.PROJECT_WITH_ID))
        assertTrue(LoginRoute.opensFrom(Routes.TRACK_EDIT_WITH_IDS))
    }

    @Test
    fun staysOnTheLoginPageWhenItIsAlreadyOpen() {
        assertFalse(LoginRoute.opensFrom(Routes.LOGIN))
        assertFalse(LoginRoute.opensFrom(Routes.COMMUNITY))
        assertFalse(LoginRoute.opensFrom(null))
    }

    @Test
    fun staysClosedWhileRecording() {
        assertFalse(LoginRoute.allowedDuring(recording = true))
        assertTrue(LoginRoute.allowedDuring(recording = false))
    }
}
