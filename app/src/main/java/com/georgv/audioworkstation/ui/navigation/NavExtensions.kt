package com.georgv.audioworkstation.ui.navigation

import androidx.navigation.NavHostController

/**
 * Prevent stacking duplicate entries when hopping between roots from Main Menu.
 *
 * Use **only** for top-level hubs ([Routes.MAIN_MENU], Library, Community, Devices).
 * Do **not** use for `project/{projectId}` — each editor open must use plain [NavHostController.navigate]
 * so back stack and ViewModel lifecycle match a detail destination.
 */
fun NavHostController.navigateSingleTopTo(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(Routes.MAIN_MENU) { saveState = true }
    }
}
