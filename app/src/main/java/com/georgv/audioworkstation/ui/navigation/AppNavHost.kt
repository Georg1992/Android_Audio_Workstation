package com.georgv.audioworkstation.ui.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.georgv.audioworkstation.ui.screens.community.CommunityScreen
import com.georgv.audioworkstation.ui.screens.devices.DevicesScreen
import com.georgv.audioworkstation.ui.screens.library.LibraryScreen
import com.georgv.audioworkstation.ui.screens.mainmenu.MainMenuScreen
import com.georgv.audioworkstation.ui.diagnostics.QuickRecordDiagnostics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.georgv.audioworkstation.ui.mixdown.ProjectMixdownViewModel
import com.georgv.audioworkstation.ui.screens.projects.CreateProjectScreen
import com.georgv.audioworkstation.ui.screens.projects.ProjectScreen
import com.georgv.audioworkstation.ui.screens.projects.TrackEditScreen
import java.util.UUID

@Composable
fun AppNavHost(
    currentLanguageTag: String,
    onSetLanguage: (String) -> Unit,
    navController: NavHostController
) {
    NavHost(
        navController = navController,
        startDestination = Routes.MAIN_MENU,
        enterTransition = { navForwardEnterTransition() },
        exitTransition = { navForwardExitTransition() },
        popEnterTransition = { navBackEnterTransition() },
        popExitTransition = { navBackExitTransition() },
    ) {
        composable(Routes.MAIN_MENU) {
            MainMenuScreen(
                currentLanguageTag = currentLanguageTag,
                onSetLanguage = onSetLanguage,
                onOpenProject = { navController.navigateSingleTopTo(Routes.CREATE_PROJECT) },
                onOpenLibrary = { navController.navigateSingleTopTo(Routes.LIBRARY) },
                onOpenCommunity = { navController.navigateSingleTopTo(Routes.COMMUNITY) },
                onOpenDevices = { navController.navigateSingleTopTo(Routes.DEVICES) },
                onQuickRecord = {
                    QuickRecordDiagnostics.markClickReceived()
                    val id = UUID.randomUUID().toString()
                    QuickRecordDiagnostics.markProjectIdGenerated(id)
                    QuickRecordDiagnostics.markNavigationRequested(id)
                    navController.navigate("${Routes.PROJECT}/$id?quick=true")
                }
            )
        }

        composable(Routes.CREATE_PROJECT) {
            CreateProjectScreen(
                onBack = { navController.popBackStack() },
                onProjectCreated = { projectId ->
                    navController.navigate("${Routes.PROJECT}/$projectId?quick=false") {
                        popUpTo(Routes.CREATE_PROJECT) { inclusive = true }
                    }
                }
            )
        }

        /**
         * Project editor/detail route — each navigation pushes a fresh destination so [ProjectScreen]
         * gets a new ViewModel instance; switching projects should go through navigation here, not by
         * rebinding an existing VM. [ProjectViewModel.scheduleBind] is only for establishing that screen's initial
         * project after composition (single binding per destination lifecycle).
         */
        composable(
            route = Routes.PROJECT_WITH_ID,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("quick") { type = NavType.BoolType; defaultValue = false }
            )
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId") ?: return@composable
            val quickRecord = entry.arguments?.getBoolean("quick") ?: false
            val mixdownVm: ProjectMixdownViewModel = hiltViewModel()

            ProjectScreen(
                projectId = projectId,
                quickRecord = quickRecord,
                onBack = { navController.popBackStack() },
                onOpenProject = { newProjectId ->
                    navController.navigate("${Routes.PROJECT}/$newProjectId?quick=false")
                },
                onConfirmMixdown = { confirmedProjectId, selectedTrackIds ->
                    Log.d(MixConfirmNavTag, "confirm_received projectId=$confirmedProjectId")
                    Log.d(
                        MixConfirmNavTag,
                        "selected_track_ids=${selectedTrackIds.joinToString(",")}",
                    )
                    Log.d(MixConfirmNavTag, "request_mixdown_called")
                    mixdownVm.requestMixdown(confirmedProjectId, selectedTrackIds)
                    Log.d(MixConfirmNavTag, "navigate_library_called")
                    navController.navigateToLibraryFromEditor()
                    Log.d(
                        MixConfirmNavTag,
                        "current_route_after_navigate=" +
                            navController.currentBackStackEntry?.destination?.route,
                    )
                },
                onEditTrack = { trackId ->
                    navController.navigate("${Routes.TRACK_EDIT}/$projectId/$trackId")
                },
            )
        }

        composable(
            route = Routes.TRACK_EDIT_WITH_IDS,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("trackId") { type = NavType.StringType },
            ),
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId") ?: return@composable
            val trackId = entry.arguments?.getString("trackId") ?: return@composable

            TrackEditScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onBack = { navController.popBackStack() },
                onOpenProject = { projectId ->
                    navController.navigate("${Routes.PROJECT}/$projectId?quick=false")
                }
            )
        }

        composable(Routes.COMMUNITY) {
            CommunityScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DEVICES) {
            DevicesScreen(onBack = { navController.popBackStack() })
        }
    }
}

private const val MixConfirmNavTag = "MixConfirmNav"
