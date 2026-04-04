package com.georgv.audioworkstation.ui.screens.projects

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.TopToolbarPanel
import com.georgv.audioworkstation.ui.components.TransportPanel
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue

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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var pendingRecordProjectName by remember(projectId) { mutableStateOf<String?>(null) }
    var isRenamingProject by remember(projectId) { mutableStateOf(false) }
    var projectNameFieldValue by remember(projectId) { mutableStateOf(TextFieldValue("")) }
    var projectNameFieldWasFocused by remember(projectId) { mutableStateOf(false) }
    var projectRenameCommitted by remember(projectId) { mutableStateOf(false) }
    val projectNameFocusRequester = remember(projectId) { FocusRequester() }

    fun commitProjectRename() {
        if (!isRenamingProject || projectRenameCommitted) return
        projectRenameCommitted = true
        isRenamingProject = false
        vm.renameProject(projectNameFieldValue.text)
    }

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

        if (quickRecord) {
            val projectName = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
                .let { "QuickRec_$it" }
            startRecordingIfPermitted(projectName)
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

    LaunchedEffect(state.project?.name, isRenamingProject) {
        if (!isRenamingProject) {
            projectNameFieldValue = TextFieldValue(state.project?.name.orEmpty())
        }
    }

    LaunchedEffect(isRenamingProject) {
        if (isRenamingProject) {
            projectNameFieldWasFocused = false
            projectRenameCommitted = false
            projectNameFocusRequester.requestFocus()
            keyboardController?.show()
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
        titleContent = {
            if (isRenamingProject) {
                BasicTextField(
                    value = projectNameFieldValue,
                    onValueChange = { projectNameFieldValue = it },
                    singleLine = true,
                    textStyle = AppText.TopBarTitle.copy(color = AppColors.Line),
                    cursorBrush = SolidColor(AppColors.Line),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(projectNameFocusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                projectNameFieldWasFocused = true
                            } else if (projectNameFieldWasFocused && !projectRenameCommitted) {
                                commitProjectRename()
                            }
                        },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            commitProjectRename()
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    )
                )
            } else {
                Text(
                    text = state.project?.name ?: stringResource(R.string.screen_project),
                    style = AppText.TopBarTitle,
                    color = AppColors.Line,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        val projectName = state.project?.name.orEmpty()
                        projectNameFieldValue = TextFieldValue(
                            text = projectName,
                            selection = TextRange(0, projectName.length)
                        )
                        isRenamingProject = true
                    }
                )
            }
        },
        onBack = if (reorderActive) null else onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TopToolbarPanel(inputLocked = reorderActive)

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
