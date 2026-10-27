package com.georgv.audioworkstation.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.ScreenState
import com.georgv.audioworkstation.core.ui.isContentEmpty
import com.georgv.audioworkstation.core.ui.resolve
import com.georgv.audioworkstation.ui.components.ProjectCard
import com.georgv.audioworkstation.ui.components.ProjectCardClicks
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.ShownCard
import com.georgv.audioworkstation.ui.components.TopToolbarPanel
import com.georgv.audioworkstation.ui.components.rememberAppLocaleScope
import com.georgv.audioworkstation.ui.components.warmLoadingBoxAsset
import com.georgv.audioworkstation.ui.navigation.NavTransitionDiagnostics
import com.georgv.audioworkstation.online.share.cardTagList
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppOpacity
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onCustomize: (String) -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    NavTransitionDiagnostics.MonitorDestinationLifecycle("library")

    val state by vm.uiState.collectAsStateWithLifecycle()
    val transport by vm.previewTransport.collectAsStateWithLifecycle()
    val shareEnabled by vm.shareEnabled.collectAsStateWithLifecycle()
    val sharingProjectIds by vm.sharingProjectIds.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        warmLoadingBoxAsset(context)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDeleteProject by remember { mutableStateOf<LibraryProjectItem?>(null) }
    val locale = rememberAppLocaleScope()

    LaunchedEffect(vm) {
        vm.userMessages.collect { message ->
            snackbarHostState.showSnackbar(message.resolve(context))
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.screen_library),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Bg)
                .padding(padding)
        ) {
            TopToolbarPanel()

            LibraryProjectContent(
                state = state,
                transport = transport,
                handlers = LibraryRowHandlers(
                    onPlay = vm::play,
                    onStop = vm::stopPreview,
                    onSeek = vm::seekPreview,
                    onOpen = { projectId ->
                        scope.launch {
                            vm.warmUpProject(projectId)
                            delay(LibraryProjectOpenWarmupMs)
                            onOpenProject(projectId)
                        }
                    },
                    onDelete = { pendingDeleteProject = it },
                    onCustomize = onCustomize,
                    onShare = vm::shareProject,
                    shareEnabled = shareEnabled,
                    sharingProjectIds = sharingProjectIds,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }

        pendingDeleteProject?.let { item ->
            val project = item.project
            AlertDialog(
                onDismissRequest = { pendingDeleteProject = null },
                containerColor = AppColors.Bg,
                tonalElevation = 0.dp,
                title = {
                    locale.Provide {
                        Text(
                            text = stringResource(R.string.library_delete_project_title),
                            style = AppText.TileTitle,
                            color = AppColors.Line
                        )
                    }
                },
                text = {
                    locale.Provide {
                        Text(
                            text = stringResource(
                                R.string.library_delete_project_message,
                                project.name?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.library_untitled_project)
                            ),
                            style = AppText.TileSubtitle,
                            color = AppColors.Line
                        )
                    }
                },
                dismissButton = {
                    locale.Provide {
                        TextButton(onClick = { pendingDeleteProject = null }) {
                            Text(
                                text = stringResource(R.string.action_cancel),
                                style = AppText.TileSubtitle,
                                color = AppColors.Line
                            )
                        }
                    }
                },
                confirmButton = {
                    locale.Provide {
                        TextButton(
                            onClick = {
                                vm.deleteProject(project.id)
                                pendingDeleteProject = null
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.action_delete),
                                style = AppText.TileSubtitle,
                                color = AppColors.Red
                            )
                        }
                    }
                }
            )
        }
    }
}

private data class LibraryRowHandlers(
    val onPlay: (LibraryProjectItem) -> Unit,
    val onStop: () -> Unit,
    val onSeek: (Int) -> Unit,
    val onOpen: (String) -> Unit,
    val onDelete: (LibraryProjectItem) -> Unit,
    val onCustomize: (String) -> Unit,
    val onShare: (String) -> Unit,
    val shareEnabled: Boolean,
    val sharingProjectIds: Set<String>,
)

@Composable
private fun LibraryProjectContent(
    state: ScreenState<LibraryContent>,
    transport: MixTransport,
    handlers: LibraryRowHandlers,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.availability, state.content.projects.size) {
        LibraryDiagnostics.logRendered(state)
    }

    when {
        state.isInitialLoad -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(Dimens.ScreenContentPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.library_loading_state),
                    style = AppText.TileSubtitle,
                    color = AppColors.iconMuted,
                )
            }
        }

        state.isContentEmpty { it.projects.isEmpty() } -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(Dimens.ScreenContentPadding)
            ) {
                Text(
                    text = stringResource(R.string.library_empty_state),
                    style = AppText.TileSubtitle,
                    color = AppColors.Line
                )
            }
        }

        else -> {
            val projects = state.content.projects
            LaunchedEffect(projects.size) {
                NavTransitionDiagnostics.logHeavyContentRendered("library", projects.size)
            }
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(Dimens.ScreenContentPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.Gap)
            ) {
                itemsIndexed(projects, key = { _, item -> item.project.id }) { _, item ->
                    val active = transport.projectId == item.project.id
                    val mixing = item.mixdown.isMixing
                    val title = item.project.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.library_untitled_project)
                    ProjectCard(
                        card = ShownCard(
                            title = title,
                            tags = cardTagList(item.project.cardTags),
                            imagePath = item.project.cardImagePath,
                            positionMs = if (active) transport.positionMs else 0,
                            durationMs = if (active) transport.durationMs else 0,
                            status = if (mixing) {
                                stringResource(
                                    R.string.library_mixing_progress_percent,
                                    (item.mixdown.progress * 100f).roundToInt(),
                                )
                            } else {
                                null
                            },
                            progress = if (mixing) item.mixdown.progress else null,
                        ),
                        clicks = ProjectCardClicks(
                            onPlay = { handlers.onPlay(item) },
                            onStop = handlers.onStop,
                            onSeek = handlers.onSeek,
                            onOpen = { handlers.onOpen(item.project.id) },
                        ),
                        actions = {
                            IconButton(onClick = { handlers.onDelete(item) }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = AppColors.Red,
                                )
                            }
                            IconButton(onClick = { handlers.onCustomize(item.project.id) }) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = stringResource(R.string.cd_customize_card),
                                    tint = AppColors.Line,
                                )
                            }
                            if (handlers.shareEnabled) {
                                val sharing = item.project.id in handlers.sharingProjectIds
                                IconButton(
                                    onClick = { handlers.onShare(item.project.id) },
                                    enabled = !sharing,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Share,
                                        contentDescription = stringResource(R.string.cd_share_project),
                                        tint = if (sharing) {
                                            AppColors.Line.copy(alpha = AppOpacity.disabled)
                                        } else {
                                            AppColors.Line
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Brief warm-up before Library → Project so the tap ripple renders before navigation. */
private const val LibraryProjectOpenWarmupMs = 70L
