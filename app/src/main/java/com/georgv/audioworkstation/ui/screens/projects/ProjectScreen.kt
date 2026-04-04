package com.georgv.audioworkstation.ui.screens.projects

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.TransportPanel
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
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
    val state by vm.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val dragController = remember { DragController() }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var pendingRecordProjectName by remember(projectId) { mutableStateOf<String?>(null) }

    val startRecordingIfPermitted: (String) -> Unit = { projectName ->
        if (state.recordingTrackId != null ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            vm.onRecordPressed(projectId, projectName)
        } else {
            pendingRecordProjectName = projectName
        }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pendingProjectName = pendingRecordProjectName
        pendingRecordProjectName = null
        if (granted && pendingProjectName != null) {
            vm.onRecordPressed(projectId, pendingProjectName)
        } else if (!granted) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Microphone permission required.")
            }
        }
    }

    LaunchedEffect(projectId, quickRecord) {
        vm.bind(projectId)

        val projectName = if (quickRecord) {
            val time = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            "QuickRec_$time"
        } else {
            "New Project"
        }

        if (quickRecord) {
            startRecordingIfPermitted(projectName)
        } else {
            vm.ensureProjectExists(projectId, projectName)
        }
    }

    LaunchedEffect(pendingRecordProjectName) {
        if (pendingRecordProjectName != null) {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
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
                onToggleSelect = vm::toggleSelect,
                onDeleteTrack = vm::deleteTrack,
                onGainChange = vm::setTrackGain,
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
                onRecord = { startRecordingIfPermitted("New Project") },
                inputLocked = reorderActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding)
            )
        }
    }
}
