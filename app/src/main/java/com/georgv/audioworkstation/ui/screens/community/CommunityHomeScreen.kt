package com.georgv.audioworkstation.ui.screens.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.resolve
import com.georgv.audioworkstation.ui.components.ProjectCard
import com.georgv.audioworkstation.ui.components.ProjectCardClicks
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.ShownCard
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityHomeScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    onOpenProject: (String) -> Unit,
    vm: CommunityHomeViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val transport by vm.transport.collectAsStateWithLifecycle()
    val images by vm.imagePaths.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val place = communityDestination(state.sessionKnown, state.email != null)

    LaunchedEffect(vm) {
        vm.userMessages.collect { message ->
            snackbarHostState.showSnackbar(message.resolve(context))
        }
    }

    LaunchedEffect(vm) {
        vm.openProject.collect { projectId -> onOpenProject(projectId) }
    }

    LaunchedEffect(Unit) {
        vm.reload()
    }

    LaunchedEffect(place) {
        if (place == CommunityDestination.SignIn) onSignedOut()
    }

    ScreenScaffold(
        title = stringResource(R.string.screen_community),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Bg)
                .padding(padding)
                .padding(Dimens.ScreenContentPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            if (state.email != null && state.name != null) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (state.preparing && state.projects.isEmpty()) {
                        Text(
                            text = stringResource(R.string.community_mix_loading),
                            style = AppText.TileSubtitle,
                            color = AppColors.iconMuted,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
                    ) {
                        items(state.projects, key = { project -> project.id }) { project ->
                            val hash = project.imageHash
                            LaunchedEffect(hash) {
                                if (hash != null) vm.loadImage(project.id, hash)
                            }
                            val active = transport.projectId == project.id
                            val title = project.title.ifBlank {
                                stringResource(R.string.library_untitled_project)
                            }
                            val loading = state.loadingProjectId == project.id || state.preparing
                            ProjectCard(
                                card = ShownCard(
                                    title = title,
                                    detail = stringResource(R.string.community_project_by, project.ownerName),
                                    status = if (loading) stringResource(R.string.community_mix_loading) else null,
                                    loading = loading,
                                    tags = project.tags,
                                    imagePath = hash?.let { images[it] },
                                    positionMs = if (active) transport.positionMs else 0,
                                    durationMs = if (active) transport.durationMs else 0,
                                ),
                                clicks = ProjectCardClicks(
                                    onPlay = { vm.play(project.id) },
                                    onStop = vm::stop,
                                    onSeek = vm::seek,
                                    onOpen = { vm.open(project.id) },
                                ),
                                actions = {},
                            )
                        }
                    }
                }
                CommunityAction(
                    text = stringResource(R.string.community_sign_out),
                    fillColor = AppColors.SurfacePanel,
                    enabled = !state.busy,
                    onClick = vm::signOut,
                )
            }
        }
    }
}
