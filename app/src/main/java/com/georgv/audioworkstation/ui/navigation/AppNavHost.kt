package com.georgv.audioworkstation.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.util.UUID
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
                    onOpenProject = { val id = UUID.randomUUID().toString()
                        navController.navigateSingleTopTo("${Routes.PROJECT}/$id?quick=false") },
                    onOpenLibrary = { navController.navigateSingleTopTo(Routes.LIBRARY) },
                    onOpenCommunity = { navController.navigateSingleTopTo(Routes.COMMUNITY) },
                    onOpenDevices = { navController.navigateSingleTopTo(Routes.DEVICES) },
                    onQuickRecord = { val id = UUID.randomUUID().toString()
                        navController.navigateSingleTopTo("${Routes.PROJECT}/$id?quick=true") },
                )
            }

        composable(
            route = Routes.PROJECT_WITH_ID,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("quick") { type = NavType.BoolType; defaultValue = false }
            )
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId") ?: return@composable
            val quickRecord = entry.arguments?.getBoolean("quick") ?: false

            ProjectScreen(
                projectId = projectId,
                quickRecord = quickRecord,
                onBack = { navController.popBackStack() }
            )
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





