package com.georgv.audioworkstation.ui.screens.projects

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.lifecycle.Lifecycle
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
import android.os.SystemClock
import android.os.Trace
import com.georgv.audioworkstation.ui.diagnostics.QuickRecordDiagnostics
import com.georgv.audioworkstation.ui.diagnostics.WaveformRecompositionDiagnostics
import androidx.compose.runtime.SideEffect
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
 * Project editor route. Loading phases:
 * 1. Lightweight shell + loader (one frame without [ProjectViewModel], then bind in background).
 * 2. Loader animates until nav transition finishes and project data is ready.
 * 3. Ready layout (chrome + tracks + waveform lanes) mounts with waveforms enabled immediately;
 *    loader crossfades out over the real track list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    projectId: String,
    quickRecord: Boolean,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit = {},
) {
    NavTransitionDiagnostics.MonitorDestinationLifecycle("project")

    if (quickRecord) {
        DisposableEffect(projectId) {
            QuickRecordDiagnostics.logMilestone("ProjectScreen quick=true", projectId)
            onDispose {
                QuickRecordDiagnostics.clearQuickNavigation(projectId, reason = "compose dispose")
            }
        }
    }
    var deferViewModel by remember(projectId) { mutableStateOf(true) }
    var loggedOpeningShell by remember(projectId) { mutableStateOf(false) }

    LaunchedEffect(projectId) {
        deferViewModel = true
        withFrameNanos { }
        deferViewModel = false
    }

    if (deferViewModel) {
        LaunchedEffect(projectId) {
            if (!loggedOpeningShell) {
                loggedOpeningShell = true
                ProjectDiagnostics.logShellRendered(projectId, phase = "openingShell")
            }
        }
        ProjectLoadingScaffold(
            message = stringResource(R.string.project_opening),
            onBack = onBack,
            onPlaceholderShown = {
                ProjectDiagnostics.logLoadingPlaceholderRendered(projectId)
            },
        )
    } else {
        ProjectScreenContent(
            projectId = projectId,
            quickRecord = quickRecord,
            onBack = onBack,
            onOpenProject = onOpenProject,
            onShellShown = {
                ProjectDiagnostics.logShellRendered(projectId, phase = "content")
            },
        )
    }
}

/** Lightweight scaffold + loader — no ViewModel, tracks, or waveforms. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectLoadingScaffold(
    message: String,
    onBack: () -> Unit,
    onPlaceholderShown: () -> Unit = {},
) {
    var loggedPlaceholder by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!loggedPlaceholder) {
            loggedPlaceholder = true
            onPlaceholderShown()
        }
    }

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
            message = message,
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
    onShellShown: () -> Unit,
) {
    val vmResolveStartMs = remember(projectId) { SystemClock.uptimeMillis() }
    val vm: ProjectViewModel = hiltViewModel()
    val topBarAlertState = rememberTopBarAlertState()

    LaunchedEffect(projectId, quickRecord) {
        if (!quickRecord) return@LaunchedEffect
        QuickRecordDiagnostics.logMilestone(
            "ProjectViewModel hiltViewModel resolved",
            projectId,
            "elapsedMs=${SystemClock.uptimeMillis() - vmResolveStartMs}",
        )
    }

    val state by vm.uiState.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.CREATED,
    )
    val destinationReady by vm.destinationReady.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.CREATED,
    )
    val navTransitionFinished = rememberProjectNavTransitionGate(projectId)
    val quickInitialTrackReady = !quickRecord || state.tracks.isNotEmpty()
    val readyToReveal = navTransitionFinished && destinationReady && quickInitialTrackReady

    var workspaceMounted by remember(projectId) { mutableStateOf(false) }
    var showWaveforms by remember(projectId) { mutableStateOf(false) }
    val loaderAlpha = remember(projectId) { Animatable(1f) }
    var loggedShell by remember(projectId) { mutableStateOf(false) }
    var loggedPlaceholder by remember(projectId) { mutableStateOf(false) }
    var loggedDestinationReady by remember(projectId) { mutableStateOf(false) }
    var loggedQuickWaiting by remember(projectId) { mutableStateOf(false) }
    var loggedQuickInitialTrack by remember(projectId) { mutableStateOf(false) }
    var loggedReadyToReveal by remember(projectId) { mutableStateOf(false) }

    QuickRecordBootstrap(
        projectId = projectId,
        quickRecord = quickRecord,
        destinationReady = destinationReady,
        vm = vm,
        state = state,
        topBarAlertState = topBarAlertState,
    )

    LaunchedEffect(projectId) {
        if (!loggedShell) {
            loggedShell = true
            onShellShown()
        }
        if (!loggedPlaceholder) {
            loggedPlaceholder = true
            ProjectDiagnostics.logLoadingPlaceholderRendered(projectId)
        }
        vm.scheduleBind(projectId)
    }

    LaunchedEffect(projectId, destinationReady) {
        if (destinationReady && !loggedDestinationReady) {
            loggedDestinationReady = true
            ProjectDiagnostics.logDestinationReady(projectId)
            if (quickRecord) {
                QuickRecordDiagnostics.logMilestone(
                    "ProjectScreen destinationReady changed true",
                    projectId,
                )
            }
        }
    }

    LaunchedEffect(quickRecord, destinationReady, navTransitionFinished, state.tracks.size) {
        if (!quickRecord) return@LaunchedEffect
        val waitingForTrack =
            destinationReady && navTransitionFinished && state.tracks.isEmpty()
        if (waitingForTrack && !loggedQuickWaiting) {
            loggedQuickWaiting = true
            ProjectDiagnostics.logQuickProjectWaitingForInitialTrack(projectId)
            QuickRecordDiagnostics.logMilestone(
                "ProjectScreen waitingForInitialTrack start",
                projectId,
            )
        }
        if (state.tracks.isNotEmpty() && !loggedQuickInitialTrack) {
            loggedQuickInitialTrack = true
            ProjectDiagnostics.logQuickProjectInitialTrackReady(projectId, state.tracks.size)
            QuickRecordDiagnostics.traceSection("QuickReadyToReveal", projectId) {
                QuickRecordDiagnostics.logMilestone(
                    "ProjectScreen initialTrackReady",
                    projectId,
                    "trackCount=${state.tracks.size}",
                )
            }
        }
    }

    LaunchedEffect(projectId, readyToReveal, quickRecord) {
        if (quickRecord && readyToReveal && !loggedReadyToReveal) {
            loggedReadyToReveal = true
            QuickRecordDiagnostics.logMilestone(
                "ProjectScreen readyToReveal changed true",
                projectId,
                "trackCount=${state.tracks.size}",
            )
            QuickRecordDiagnostics.clearQuickNavigation(projectId, reason = "readyToReveal")
        }
    }

    LaunchedEffect(projectId, readyToReveal) {
        if (!readyToReveal) {
            workspaceMounted = false
            showWaveforms = false
            loaderAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        workspaceMounted = true
        showWaveforms = true
        loaderAlpha.snapTo(1f)
        withFrameNanos { }
        ProjectDiagnostics.logReadyLayoutMounted(
            projectId,
            trackCount = state.tracks.size,
            showWaveforms = true,
            quick = quickRecord,
            navMaxGapMs = NavTransitionDiagnostics.peekMaxFrameGapMs(),
        )
        ProjectDiagnostics.logWaveformsEnabled(projectId, state.tracks.size)
        ProjectDiagnostics.logLoadingFadeStarted(projectId)
        loaderAlpha.animateTo(0f, tween(ProjectLoadingCrossfadeMs))
        ProjectDiagnostics.logLoadingFadeFinished(projectId)
        withFrameNanos { }
    }

    val openingMessage = stringResource(R.string.project_opening)
    val loadingTracksMessage = stringResource(R.string.project_loading_tracks)
    val placeholderMessage =
        if (destinationReady) loadingTracksMessage else openingMessage
    var trackPagingSummary by remember(projectId) { mutableStateOf("1/1") }

    ScreenScaffold(
        topBarAlertMessage = if (workspaceMounted) topBarAlertState.message else null,
        titleContent = {
            if (workspaceMounted) {
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
            if (workspaceMounted) {
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
            if (workspaceMounted) {
                ProjectScreenHeavyLayer(
                    vm = vm,
                    projectId = projectId,
                    showWaveforms = showWaveforms,
                    onOpenProject = onOpenProject,
                    topBarAlertState = topBarAlertState,
                    onTrackPagingSummaryChange = { trackPagingSummary = it },
                )
            }
            if (loaderAlpha.value > 0f) {
                AppMusicLoadingPlaceholder(
                    message = placeholderMessage,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(loaderAlpha.value),
                )
            }
        }
    }
}

/** True after the 420ms nav slide window — loader stays visible until data is also ready. */
@Composable
private fun rememberProjectNavTransitionGate(projectId: String): Boolean {
    var gateOpen by remember(projectId) { mutableStateOf(false) }

    LaunchedEffect(projectId) {
        gateOpen = false
        delay(ProjectNavTransitionGateMs)
        gateOpen = true
        ProjectDiagnostics.logNavTransitionGateOpened(projectId, ProjectNavTransitionGateMs)
    }

    return gateOpen
}

/** Starts Quick Record while the loader is still visible — before workspace mount. */
@Composable
private fun QuickRecordBootstrap(
    projectId: String,
    quickRecord: Boolean,
    destinationReady: Boolean,
    vm: ProjectViewModel,
    state: ProjectUiState,
    topBarAlertState: TopBarAlertState,
) {
    if (!quickRecord) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var pendingRecordProjectName by remember(projectId) { mutableStateOf<String?>(null) }
    var quickRecordStarted by remember(projectId) { mutableStateOf(false) }
    var permissionCheckStartMs by remember(projectId) { mutableStateOf(0L) }
    val microphonePermissionError = stringResource(R.string.error_microphone_permission_required)

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pendingProjectName = pendingRecordProjectName
        pendingRecordProjectName = null
        QuickRecordDiagnostics.logStepEnd(
            "QuickRecordBootstrap permission check",
            permissionCheckStartMs,
            projectId,
            "granted=$granted",
        )
        if (granted && pendingProjectName != null) {
            QuickRecordDiagnostics.logMilestone(
                "QuickRecordBootstrap recording start requested",
                projectId,
                "via=permissionLauncher projectName=$pendingProjectName",
            )
            vm.onRecordPressed(projectId, pendingProjectName)
        } else if (!granted) {
            topBarAlertState.show(coroutineScope, microphonePermissionError)
        }
    }

    LaunchedEffect(pendingRecordProjectName) {
        if (pendingRecordProjectName != null) {
            permissionCheckStartMs = SystemClock.uptimeMillis()
            QuickRecordDiagnostics.logStepStart("QuickRecordBootstrap permission check", projectId)
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(projectId, quickRecord, destinationReady) {
        if (!quickRecord || !destinationReady || quickRecordStarted) return@LaunchedEffect
        quickRecordStarted = true
        val bootstrapStartMs = SystemClock.uptimeMillis()
        Trace.beginSection("QuickRecordBootstrap")
        QuickRecordDiagnostics.logStepStart("QuickRecordBootstrap", projectId)
        val projectName = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            .let { "QuickRec_$it" }
        if (state.isRecordingStartup) {
            Trace.endSection()
            return@LaunchedEffect
        }
        if (state.recordingTrackId != null) {
            QuickRecordDiagnostics.logMilestone(
                "QuickRecordBootstrap recording start requested",
                projectId,
                "via=existingRecording projectName=$projectName",
            )
            vm.onRecordPressed(projectId, projectName)
        } else {
            val permissionStartMs = SystemClock.uptimeMillis()
            QuickRecordDiagnostics.logStepStart("QuickRecordBootstrap permission check", projectId)
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            QuickRecordDiagnostics.logStepEnd(
                "QuickRecordBootstrap permission check",
                permissionStartMs,
                projectId,
                "granted=$granted",
            )
            if (granted) {
                QuickRecordDiagnostics.logMilestone(
                    "QuickRecordBootstrap recording start requested",
                    projectId,
                    "via=alreadyGranted projectName=$projectName",
                )
                vm.onRecordPressed(projectId, projectName)
            } else {
                pendingRecordProjectName = projectName
            }
        }
        QuickRecordDiagnostics.logStepEnd("QuickRecordBootstrap", bootstrapStartMs, projectId)
        Trace.endSection()
    }
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
    showWaveforms: Boolean,
    onOpenProject: (String) -> Unit,
    topBarAlertState: TopBarAlertState,
    onTrackPagingSummaryChange: (String) -> Unit,
) {
    val structuralState by vm.structuralUiState.collectAsStateWithLifecycle()
    val realtimeState by vm.realtimeUiState.collectAsStateWithLifecycle()
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
        if (structuralState.isRecordingStartup) return
        if (structuralState.recordingTrackId != null) {
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

    val playheadPositionMs = scrubbingPlayheadPositionMs ?: realtimeState.playheadPositionMs

    Column(modifier = Modifier.fillMaxSize()) {
        TimelinePlayheadScrubberPanel(
            playheadPositionMs = playheadPositionMs,
            timelineDurationMs = realtimeState.timelineVisibleDurationMs,
            masterPeakDbText = realtimeState.masterPeakDbText,
            masterPeakIndicatorLevel = realtimeState.masterPeakIndicatorLevel,
            onMasterPeakIndicatorClick = { vm.onMasterPeakIndicatorClicked() },
            onPlayheadScrubStarted = { vm.onPlayheadScrubStarted() },
            onPlayheadScrubCancelled = {
                scrubbingPlayheadPositionMs = null
                vm.onPlayheadScrubCancelled()
            },
            onPlayheadPositionPreview = { positionMs ->
                scrubbingPlayheadPositionMs = positionMs
                vm.onPlayheadScrubPreviewPosition(positionMs, realtimeState.timelineVisibleDurationMs)
            },
            onPlayheadPositionCommit = { positionMs ->
                scrubbingPlayheadPositionMs = null
                vm.onPlayheadScrubCommittedPosition(positionMs, realtimeState.timelineVisibleDurationMs)
            },
            inputLocked =
                reorderActive ||
                structuralState.recordingTrackId != null ||
                structuralState.isRecordingStartup,
        )

        ProjectHeavyWorkspace(
            state = structuralState,
            projectId = projectId,
            showWaveforms = showWaveforms,
            dragController = dragController,
            realtimeUiState = vm.realtimeUiState,
            onTrackPagingSummaryChange = onTrackPagingSummaryChange,
            vm = vm,
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
                isRecording = structuralState.recordingTrackId != null || structuralState.isRecordingStartup,
                isPlaying = structuralState.isTransportPlaying,
                isPlayEnabled = structuralState.isPlayEnabled,
                isStopEnabled = structuralState.isStopEnabled,
                stopButtonShowsPause = structuralState.stopButtonShowsPause,
                playheadTimeLabel = formatTimelineDuration(playheadPositionMs),
                onPlay = { vm.onPlayPressed() },
                onStop = { vm.onStopPressed() },
                onRecord = { startRecordingIfPermitted("New Project") },
                isRecordEnabled = !structuralState.isImportInProgress,
                inputLocked = reorderActive,
                modifier = Modifier.fillMaxWidth(TransportPanelWidthFraction),
            )

            Spacer(Modifier.weight(1f))

            ImportAudioButton(
                enabled =
                    structuralState.recordingTrackId == null &&
                    !structuralState.isRecordingStartup &&
                    !structuralState.isImportInProgress,
                onClick = { importAudioLauncher.launch(IMPORT_AUDIO_MIME_TYPES) },
                inputLocked = reorderActive,
            )
        }
    }
}

@Composable
private fun ProjectHeavyWorkspace(
    state: ProjectUiState,
    projectId: String,
    showWaveforms: Boolean,
    dragController: DragController,
    realtimeUiState: kotlinx.coroutines.flow.StateFlow<ProjectRealtimeUiState>,
    onTrackPagingSummaryChange: (String) -> Unit,
    vm: ProjectViewModel,
    modifier: Modifier = Modifier,
) {
    SideEffect {
        WaveformRecompositionDiagnostics.logHeavyWorkspaceRecomposition(
            projectId,
            state.waveformStatesByTrackId,
        )
    }

    LaunchedEffect(projectId, showWaveforms, state.tracks.size, state.waveformStatesByTrackId) {
        WaveformRecompositionDiagnostics.logHeavyWorkspaceLaunchedEffect(
            projectId = projectId,
            trigger = "keys(projectId,showWaveforms,tracks.size,waveformStatesByTrackId)",
            waveformStates = state.waveformStatesByTrackId,
        )
        ProjectDiagnostics.logHeavyWorkspaceRendered(
            projectId,
            state.tracks.size,
            showWaveforms,
        )
        ProjectDiagnostics.logTrackWaveformStates(
            projectId,
            state.waveformStatesByTrackId,
            state.tracks.map { it.id },
        )
    }

    ProjectTrackList(
        tracks = state.tracks,
        selectedTrackIds = state.selectedTrackIds,
        recordingTrackId = state.recordingTrackId,
        recordTargetTrackId = state.recordTargetTrackId,
        sessionTrackIds = state.sessionTrackIds,
        importInProgress = state.isImportInProgress,
        realtimeUiState = realtimeUiState,
        timelineClipsByTrackId = state.timelineClipsByTrackId,
        timelineLaneLayoutDurationMs = state.timelineLaneLayoutDurationMs,
        playbackActive = state.playbackSessionActive,
        showWaveforms = showWaveforms,
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
        modifier = modifier,
    )
}

private val IMPORT_AUDIO_MIME_TYPES = arrayOf(
    "audio/wav",
    "audio/x-wav",
    "audio/vnd.wave",
    "audio/wave",
    "audio/mpeg",
    "audio/mp3",
)

/** Just past the 420ms nav slide — loader stays until [destinationReady] as well. */
private const val ProjectNavTransitionGateMs = 450L

/** Fade loader out before mounting workspace. */
private const val ProjectLoadingCrossfadeMs = 180
