package com.georgv.audioworkstation.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.georgv.audioworkstation.core.localization.LanguageViewModel
import com.georgv.audioworkstation.ui.MainViewModel
import com.georgv.audioworkstation.ui.screens.community.CommunityScreen
import com.georgv.audioworkstation.ui.screens.devices.DevicesScreen
import com.georgv.audioworkstation.ui.screens.library.LibraryScreen
import com.georgv.audioworkstation.ui.screens.mainmenu.MainMenuScreen
import com.georgv.audioworkstation.ui.screens.projects.ProjectScreen


@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Routes.MAIN_MENU,
        modifier = Modifier
    ) {
        composable(Routes.MAIN_MENU) {
            MainMenuScreen(
                onOpenProject = { navController.navigateSingleTopTo(Routes.PROJECT) },
                onOpenLibrary = { navController.navigateSingleTopTo(Routes.LIBRARY) },
                onOpenCommunity = { navController.navigateSingleTopTo(Routes.COMMUNITY) },
                onOpenDevices = { navController.navigateSingleTopTo(Routes.DEVICES) },
                onQuickRecord = { navController.navigateSingleTopTo(Routes.PROJECT) },
            )
        }

        composable(Routes.PROJECT) {
            ProjectScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.COMMUNITY) {
            CommunityScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DEVICES) {
            DevicesScreen(onBack = { navController.popBackStack() })
        }
    }
}





