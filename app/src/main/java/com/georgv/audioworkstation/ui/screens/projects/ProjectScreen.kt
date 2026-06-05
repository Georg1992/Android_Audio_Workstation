package com.georgv.audioworkstation.ui.screens.projects

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.ContentResolverAudioImportSource
import com.georgv.audioworkstation.core.content.resolveDisplayName
import com.georgv.audioworkstation.core.ui.resolve
import com.georgv.audioworkstation.ui.components.AppMusicLoadingPlaceholder
import com.georgv.audioworkstation.ui.components.ImportAudioButton
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.TimelinePlayheadScrubberPanel
import com.georgv.audioworkstation.ui.components.TransportPanel
import com.georgv.audioworkstation.ui.components.formatTimelineDuration
import com.georgv.audioworkstation.ui.components.rememberTopBarAlertState
import com.georgv.audioworkstation.ui.components.TopBarAlertState
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.navigation.NavTransitionDiagnostics
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens
import com.georgv.audioworkstation.ui.theme.TransportPanelWidthFraction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Project editor route. Loading is staged in three phases:
 * 1. [ProjectOpeningShell] — one frame without [ProjectViewModel] so notes animate during nav.
 * 2. Placeholder — [AppMusicLoadingPlaceholder] until bind + nav gate complete.
 * 3. Heavy workspace — fades in after placeholder dims out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    projectId: String,
    quickRecord: Boolean,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit = {},
) {
    SideEffect {
        ProjectDiagnostics.logShellRenderedImmediately(projectId)
    }
    NavTransitionDiagnostics.MonitorDestinationLifecycle("project")

    var viewModelReady by remember(projectId) { mutableStateOf(false) }
    LaunchedEffect(projectId) {
        viewModelReady = false
        withFrameNanos { }
        viewModelReady = true
    }

    if (!viewModelReady) {
        ProjectOpeningShell(onBack = onBack)
    } else {
        ProjectScreenContent(
            projectId = projectId,
            quickRecord = quickRecord,
            onBack = onBack,
            onOpenProject = onOpenProject,
        )
    }
}

/** Lightweight shell shown for one frame before [hiltViewModel] construction. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectOpeningShell(onBack: () -> Unit) {
    ScreenScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.screen_project),
                style = AppText.TopBarTitle,
                color = AppColors.Line,
            )
        },
        onBack = onBack,
    ) { padding ->
        AppMusicLoadingPlaceholder(
            message = stringResource(R.string.project_opening),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectScreenContent(
    projectId: String,
    quickRecord: Boolean,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    vm: ProjectViewModel = hiltViewModel(),
) {
    val destinationReady by vm.destinationReady.collectAsStateWithLifecycle()
    val transitionGateOpen = rememberProjectHeavyContentGate(projectId)
    val readyToShowData = transitionGateOpen && destinationReady
    var placeholderVisible by remember(projectId) { mutableStateOf(true) }
    var showHeavyWorkspace by remember(projectId) { mutableStateOf(false) }

    LaunchedEffect(projectId, readyToShowData) {
        if (!readyToShowData) {
            placeholderVisible = true
            showHeavyWorkspace = false
            return@LaunchedEffect
        }
        placeholderVisible = false
        delay(PROJECT_PLACEHOLDER_DIM_OUT_MS)
        showHeavyWorkspace = true
    }

    val openingMessage = stringResource(R.string.project_opening)
    val loadingTracksMessage = stringResource(R.string.project_loading_tracks)
    val placeholderMessage =
        if (destinationReady) loadingTracksMessage else openingMessage
    var trackPagingSummary by remember(projectId) { mutableStateOf("1/1") }
    val topBarAlertState = rememberTopBarAlertState()

    LaunchedEffect(projectId) {
        ProjectDiagnostics.logBindStarted(projectId)
        vm.scheduleBind(projectId)
    }

    ScreenScaffold(
        topBarAlertMessage = if (showHeavyWorkspace) topBarAlertState.message else null,
        titleContent = {
            if (showHeavyWorkspace) {
                ProjectScreenTitleBar(projectId = projectId, vm = vm)
            } else {
                Text(
                    text = stringResource(R.string.screen_project),
                    style = AppText.TopBarTitle,
                    color = AppColors.Line,
                )
            }
        },
        onBack = onBack,
        actions = {
            if (showHeavyWorkspace) {
                ProjectScreenTitleActions(trackPagingSummary = trackPagingSummary)
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Bg)
                .padding(padding),
        ) {
            AnimatedVisibility(
                visible = placeholderVisible,
                exit = fadeOut(animationSpec = tween(PROJECT_PLACEHOLDER_DIM_OUT_MS.toInt())),
                modifier = Modifier.fillMaxSize(),
            ) {
                AppMusicLoadingPlaceholder(
                    message = placeholderMessage,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AnimatedVisibility(
                visible = showHeavyWorkspace,
                enter = fadeIn(animationSpec = tween(PROJECT_HEAVY_CONTENT_FADE_IN_MS)),
                modifier = Modifier.fillMaxSize(),
            ) {
                ProjectScreenHeavyLayer(
                    vm = vm,
                    projectId = projectId,
                    quickRecord = quickRecord,
                    onOpenProject = onOpenProject,
                    topBarAlertState = topBarAlertState,
                    onTrackPagingSummaryChange = { trackPagingSummary = it },
                )
            }
        }
    }
}

/** Defers heavy workspace composition until after the nav slide window (visual only). */
@Composable
private fun rememberProjectHeavyContentGate(projectId: String): Boolean {
    var gateOpen by remember(projectId) { mutableStateOf(false) }

    LaunchedEffect(projectId) {
        gateOpen = false
        delay(PROJECT_HEAVY_CONTENT_GATE_DELAY_MS)
        gateOpen = true
        ProjectDiagnostics.logContentGateOpened(projectId, PROJECT_HEAVY_CONTENT_GATE_DELAY_MS)
    }

    return gateOpen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectScreenTitleBar(
    projectId: String,
    vm: ProjectViewModel,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
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
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    commitProjectRename()
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
            ),
        )
    } else {
        Text(
            text = state.project?.name ?: stringResource(R.string.screen_project),
            style = AppText.TopBarTitle,
            color = AppColors.Line,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                val projectName = state.project?.name.orEmpty()
                projectNameFieldValue = TextFieldValue(
                    text = projectName,
                    selection = TextRange(0, projectName.length),
                )
                isRenamingProject = true
            },
        )
    }
}

@Composable
private fun ProjectScreenTitleActions(trackPagingSummary: String) {
    Text(
        text = trackPagingSummary,
        style = AppText.TopBarTitle.copy(fontSize = 11.sp),
        color = AppColors.Line.copy(alpha = 0.72f),
        modifier = Modifier.padding(end = Dimens.TileInnerPadding),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectScreenHeavyLayer(
    vm: ProjectViewModel,
    projectId: String,
    quickRecord: Boolean,
    onOpenProject: (String) -> Unit,
    topBarAlertState: TopBarAlertState,
    onTrackPagingSummaryChange: (String) -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val sampleRateMismatchDialog by vm.sampleRateMismatchDialogState.collectAsStateWithLifecycle()
    val dragController = remember { DragController() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var pendingRecordProjectName by remember(projectId) { mutableStateOf<String?>(null) }
    var scrubbingPlayheadPositionMs by remember { mutableStateOf<Long?>(null) }

    val microphonePermissionError = stringResource(R.string.error_microphone_permission_required)
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pendingProjectName = pendingRecordProjectName
        pendingRecordProjectName = null
        if (granted && pendingProjectName != null) {
            vm.onRecordPressed(projectId, pendingProjectName)
        } else if (!granted) {
            topBarAlertState.show(coroutineScope, microphonePermissionError)
        }
    }

    val importAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val source = ContentResolverAudioImportSource(context.contentResolver, uri)
            vm.importAudio(projectId, source, resolveDisplayName(context, uri))
        }
    }

    fun startRecordingIfPermitted(projectName: String) {
        if (state.isRecordingStartup) return
        if (state.recordingTrackId != null) {
            vm.onRecordPressed(projectId, projectName)
        } else if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.onRecordPressed(projectId, projectName)
        } else {
            pendingRecordProjectName = projectName
        }
    }

    LaunchedEffect(projectId, quickRecord) {
        if (!quickRecord) return@LaunchedEffect
        val projectName = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            .let { "QuickRec_$it" }
        startRecordingIfPermitted(projectName)
    }

    LaunchedEffect(pendingRecordProjectName) {
        if (pendingRecordProjectName != null) {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(vm) {
        coroutineScope {
            launch {
                vm.userMessages.collect { message ->
                    topBarAlertState.show(coroutineScope, message.resolve(context))
                }
            }
            launch {
                vm.openProjectRequests.collect { newProjectId ->
                    onOpenProject(newProjectId)
                }
            }
        }
    }

    sampleRateMismatchDialog?.let { dialog ->
        SampleRateMismatchImportDialog(
            dialog = dialog,
            onImportWithResampling = { vm.confirmImportWithResampling(projectId) },
            onCreateProject = { vm.confirmCreateProjectForImport() },
            onCancel = vm::cancelSampleRateMismatchImport,
        )
    }

    val reorderActive = dragController.isDragging
    LaunchedEffect(reorderActive) {
        if (reorderActive) scrubbingPlayheadPositionMs = null
    }

    ProjectHeavyWorkspace(
            state = state,
            projectId = projectId,
            dragController = dragController,
            scrubbingPlayheadPositionMs = scrubbingPlayheadPositionMs,
            onScrubbingPlayheadPositionChange = { scrubbingPlayheadPositionMs = it },
            onTrackPagingSummaryChange = onTrackPagingSummaryChange,
            reorderActive = reorderActive,
            onStartRecording = { startRecordingIfPermitted("New Project") },
            onImportAudio = { importAudioLauncher.launch(IMPORT_AUDIO_MIME_TYPES) },
            vm = vm,
        )
}

@Composable
private fun ProjectHeavyWorkspace(
    state: ProjectUiState,
    projectId: String,
    dragController: DragController,
    scrubbingPlayheadPositionMs: Long?,
    onScrubbingPlayheadPositionChange: (Long?) -> Unit,
    onTrackPagingSummaryChange: (String) -> Unit,
    reorderActive: Boolean,
    onStartRecording: () -> Unit,
    onImportAudio: () -> Unit,
    vm: ProjectViewModel,
) {
    LaunchedEffect(projectId, state.tracks.size) {
        ProjectDiagnostics.logHeavyWorkspaceRendered(projectId, state.tracks.size)
    }

    val playheadPositionMs = scrubbingPlayheadPositionMs ?: state.playheadPositionMs
    Column(modifier = Modifier.fillMaxSize()) {
        TimelinePlayheadScrubberPanel(
            playheadPositionMs = playheadPositionMs,
            timelineDurationMs = state.timelineVisibleDurationMs,
            masterPeakDbText = state.masterPeakDbText,
            masterPeakIndicatorLevel = state.masterPeakIndicatorLevel,
            onMasterPeakIndicatorClick = { vm.onMasterPeakIndicatorClicked() },
            onPlayheadScrubStarted = { vm.onPlayheadScrubStarted() },
            onPlayheadScrubCancelled = {
                onScrubbingPlayheadPositionChange(null)
                vm.onPlayheadScrubCancelled()
            },
            onPlayheadPositionPreview = { positionMs ->
                onScrubbingPlayheadPositionChange(positionMs)
                vm.onPlayheadScrubPreviewPosition(positionMs, state.timelineVisibleDurationMs)
            },
            onPlayheadPositionCommit = { positionMs ->
                onScrubbingPlayheadPositionChange(null)
                vm.onPlayheadScrubCommittedPosition(positionMs, state.timelineVisibleDurationMs)
            },
            inputLocked =
                reorderActive ||
                state.recordingTrackId != null ||
                state.isRecordingStartup,
        )

        ProjectTrackList(
            tracks = state.tracks,
            selectedTrackIds = state.selectedTrackIds,
            recordingTrackId = state.recordingTrackId,
            recordTargetTrackId = state.recordTargetTrackId,
            importInProgress = state.isImportInProgress,
            recordingInputLevel = state.recordingInputLevel,
            timelineClipsByTrackId = state.timelineClipsByTrackId,
            timelineLaneLayoutDurationMs = state.timelineLaneLayoutDurationMs,
            timelineVisibleDurationMs = state.timelineVisibleDurationMs,
            timelinePlayheadPositionMs = playheadPositionMs,
            playbackActive = state.playbackSessionActive,
            dragController = dragController,
            onToggleSelect = vm::toggleSelect,
            onDeleteTrack = vm::deleteTrack,
            onCancelImport = vm::cancelImport,
            onGainChange = vm::setTrackGain,
            onGainCommit = vm::commitTrackGain,
            onPanChange = vm::setTrackPan,
            onPanCommit = vm::commitTrackPan,
            onRenameTrack = vm::renameTrack,
            onToggleRecordTarget = vm::toggleRecordTarget,
            onToggleLoop = vm::toggleTrackLoop,
            onUpdateTrackLoopRegion = vm::updateTrackLoopRegion,
            onReorderTracks = { vm.setTrackOrderSession(projectId, it) },
            onPersistTrackOrder = { vm.persistTrackOrderToDb(projectId) },
            onTrackPagingSummaryChange = onTrackPagingSummaryChange,
            modifier = Modifier.weight(1f),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            TransportPanel(
                isRecording = state.recordingTrackId != null || state.isRecordingStartup,
                isPlaying = state.isTransportPlaying,
                isPlayEnabled = state.isPlayEnabled,
                isStopEnabled = state.isStopEnabled,
                stopButtonShowsPause = state.stopButtonShowsPause,
                playheadTimeLabel = formatTimelineDuration(playheadPositionMs),
                onPlay = { vm.onPlayPressed() },
                onStop = { vm.onStopPressed() },
                onRecord = onStartRecording,
                isRecordEnabled = !state.isImportInProgress,
                inputLocked = reorderActive,
                modifier = Modifier.fillMaxWidth(TransportPanelWidthFraction),
            )

            Spacer(Modifier.weight(1f))

            ImportAudioButton(
                enabled = state.recordingTrackId == null && !state.isRecordingStartup && !state.isImportInProgress,
                onClick = onImportAudio,
                inputLocked = reorderActive,
            )
        }
    }
}

private val IMPORT_AUDIO_MIME_TYPES = arrayOf(
    "audio/wav",
    "audio/x-wav",
    "audio/vnd.wave",
    "audio/wave",
    "audio/mpeg",
    "audio/mp3",
)

/** ~450ms after compose — just past the 420ms nav slide; visual gate only, VM bind is not delayed. */
private const val PROJECT_HEAVY_CONTENT_GATE_DELAY_MS = 450L

private const val PROJECT_PLACEHOLDER_DIM_OUT_MS = 120L
private const val PROJECT_HEAVY_CONTENT_FADE_IN_MS = 150
