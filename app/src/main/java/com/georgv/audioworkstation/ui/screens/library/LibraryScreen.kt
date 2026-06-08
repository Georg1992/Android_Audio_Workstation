package com.georgv.audioworkstation.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.ScreenState
import com.georgv.audioworkstation.core.ui.isContentEmpty
import com.georgv.audioworkstation.core.ui.resolve
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.TopToolbarPanel
import com.georgv.audioworkstation.ui.components.warmLoadingBoxAsset
import com.georgv.audioworkstation.ui.navigation.NavTransitionDiagnostics
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppOpacity
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlin.math.roundToInt
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    vm: LibraryViewModel = hiltViewModel()
) {
    NavTransitionDiagnostics.MonitorDestinationLifecycle("library")

    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        warmLoadingBoxAsset(context)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDeleteProject by remember { mutableStateOf<LibraryProjectItem?>(null) }

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
                onProjectCardBodyClick = vm::onProjectCardBodyClick,
                onOpenProject = { projectId ->
                    scope.launch {
                        vm.warmUpProject(projectId)
                        delay(LibraryProjectOpenWarmupMs)
                        onOpenProject(projectId)
                    }
                },
                onDeleteClick = { pendingDeleteProject = it },
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
                    Text(
                        text = stringResource(R.string.library_delete_project_title),
                        style = AppText.TileTitle,
                        color = AppColors.Line
                    )
                },
                text = {
                    Text(
                        text = stringResource(
                            R.string.library_delete_project_message,
                            project.name?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.library_untitled_project)
                        ),
                        style = AppText.TileSubtitle,
                        color = AppColors.Line
                    )
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteProject = null }) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            style = AppText.TileSubtitle,
                            color = AppColors.Line
                        )
                    }
                },
                confirmButton = {
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
            )
        }
    }
}

@Composable
private fun LibraryProjectContent(
    state: ScreenState<LibraryContent>,
    onProjectCardBodyClick: (LibraryProjectItem) -> Unit,
    onOpenProject: (String) -> Unit,
    onDeleteClick: (LibraryProjectItem) -> Unit,
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
                    LibraryProjectRow(
                        item = item,
                        onBodyClick = { onProjectCardBodyClick(item) },
                        onOpenProjectClick = { onOpenProject(item.project.id) },
                        onDeleteClick = { onDeleteClick(item) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryProjectRow(
    item: LibraryProjectItem,
    onBodyClick: () -> Unit,
    onOpenProjectClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val projectName =
        item.project.name?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.library_untitled_project)
    val mixdown = item.mixdown
    val mixFileExists = mixdown.mixdownWavPath?.let { File(it).isFile } == true
    val previewEnabled = mixdown.hasMixPreview && mixFileExists && !mixdown.isMixing
    val subtitleResId =
        libraryCardSubtitleResId(
            mixdown = mixdown,
            isCurrentlyPlaying = item.isPreviewPlaying,
            mixFileExists = mixFileExists,
        )
    val shape = RoundedCornerShape(Dimens.TileRadius)

    Surface(
        onClick = onBodyClick,
        enabled = !mixdown.isMixing,
        shape = shape,
        color = AppColors.SurfacePanel,
        shadowElevation = Dimens.Stroke,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(Dimens.Stroke, AppColors.Line, shape)
                .padding(Dimens.TileInnerPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = projectName,
                    style = AppText.TileTitle,
                    color = AppColors.Line
                )
                Text(
                    text =
                        if (mixdown.isMixing) {
                            stringResource(
                                R.string.library_mixing_progress_percent,
                                (mixdown.progress * 100f).roundToInt(),
                            )
                        } else {
                            stringResource(subtitleResId)
                        },
                    style = AppText.TileSubtitle,
                    color =
                        if (previewEnabled || mixdown.isMixing) {
                            AppColors.iconMuted
                        } else {
                            AppColors.iconMuted.copy(alpha = AppOpacity.disabled)
                        },
                    modifier = Modifier.alpha(if (previewEnabled || mixdown.isMixing) 1f else AppOpacity.disabled),
                )
                if (mixdown.isMixing) {
                    LinearProgressIndicator(
                        progress = { mixdown.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Dimens.Gap / 2),
                        color = AppColors.Line,
                        trackColor = AppColors.iconMuted.copy(alpha = 0.24f),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap / 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenProjectClick) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = stringResource(R.string.cd_open_project),
                        tint = AppColors.Line,
                    )
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = AppColors.Red
                    )
                }
            }
        }
    }
}

/** Brief warm-up before Library → Project so the tap ripple renders before navigation. */
private const val LibraryProjectOpenWarmupMs = 70L
