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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.TopToolbarPanel
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    vm: LibraryViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDeleteProject by remember { mutableStateOf<ProjectEntity?>(null) }

    LaunchedEffect(vm) {
        vm.userMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
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

            if (state.projects.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(Dimens.ScreenContentPadding)
                ) {
                    Text(
                        text = stringResource(R.string.library_empty_state),
                        style = AppText.TileSubtitle,
                        color = AppColors.Line
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(Dimens.ScreenContentPadding),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Gap)
                ) {
                    items(state.projects, key = { it.id }) { project ->
                        LibraryProjectRow(
                            projectName = project.name?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.library_untitled_project),
                            onClick = { onOpenProject(project.id) },
                            onDeleteClick = { pendingDeleteProject = project }
                        )
                    }
                }
            }
        }

        pendingDeleteProject?.let { project ->
            AlertDialog(
                onDismissRequest = { pendingDeleteProject = null },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryProjectRow(
    projectName: String,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val shape = RoundedCornerShape(Dimens.TileRadius)

    Surface(
        onClick = onClick,
        shape = shape,
        color = AppColors.Bg,
        shadowElevation = Dimens.Stroke,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(Dimens.Stroke, AppColors.Line, shape)
                .padding(Dimens.TileInnerPadding),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = projectName,
                    style = AppText.TileTitle,
                    color = AppColors.Line
                )
                Text(
                    text = stringResource(R.string.library_open_project_hint),
                    style = AppText.TileSubtitle,
                    color = AppColors.Line.copy(alpha = 0.65f)
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
