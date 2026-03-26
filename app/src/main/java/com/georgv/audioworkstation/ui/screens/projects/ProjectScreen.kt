package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.TransportPanel
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlinx.coroutines.yield
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    projectId: String,
    quickRecord: Boolean,
    onBack: () -> Unit,
    vm: ProjectViewModel = hiltViewModel()
) {
    LaunchedEffect(projectId, quickRecord) {
        vm.bind(projectId)

        val projectName = if (quickRecord) {
            val time = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            "QuickRec_$time"
        } else {
            "New Project"
        }

        vm.ensureProjectExists(projectId, projectName)

        if (quickRecord) {
            vm.addTrack(projectId)
        }
    }

    val state by vm.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val dragController = remember { DragController() }
    val snackbarHostState = remember { SnackbarHostState() }

    val sessionGainByTrackId = remember { mutableStateMapOf<String, Float>() }
    LaunchedEffect(projectId) {
        sessionGainByTrackId.clear()
    }

    LaunchedEffect(vm) {
        vm.userMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(state.recordingTrackId, state.tracks.size) {
        val id = state.recordingTrackId ?: return@LaunchedEffect
        yield()
        val index = vm.uiState.value.tracks.indexOfFirst { it.id == id }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
    }

    val reorderActive = dragController.isDragging

    ScreenScaffold(
        title = state.project?.name ?: stringResource(R.string.screen_project),
        onBack = if (reorderActive) null else onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ProjectTimelinePlaceholder(reorderActive = reorderActive)

            ProjectTrackList(
                tracks = state.tracks,
                selectedTrackIds = state.selectedTrackIds,
                recordingTrackId = state.recordingTrackId,
                listState = listState,
                dragController = dragController,
                sessionGainByTrackId = sessionGainByTrackId,
                onToggleSelect = vm::toggleSelect,
                onDeleteTrack = vm::deleteTrack,
                onRenameTrack = vm::renameTrack,
                onReorderTracks = { vm.setTrackOrderSession(projectId, it) },
                onPersistTrackOrder = { vm.persistTrackOrderToDb(projectId) },
                modifier = Modifier.weight(1f)
            )

            TransportPanel(
                isRecording = state.recordingTrackId != null,
                isPlaying = state.playingTrackIds.isNotEmpty(),
                isPlayEnabled = state.isPlayEnabled,
                isStopEnabled = state.isStopEnabled,
                onPlay = { vm.onPlayPressed() },
                onStop = { vm.onStopPressed() },
                onRecord = { vm.onRecordPressed(projectId) },
                inputLocked = reorderActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding)
            )
        }
    }
}
