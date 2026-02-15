package com.georgv.audioworkstation.ui.navigation

import androidx.navigation.NavHostController

/**
 * Prevent stacking the same destination many times when user taps fast.
 * Use for top-level destinations.
 */
fun NavHostController.navigateSingleTopTo(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(Routes.MAIN_MENU) { saveState = true }
    }
}
