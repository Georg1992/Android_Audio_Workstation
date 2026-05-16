package com.georgv.audioworkstation.ui.screens.projects

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.AudioImportSource
import com.georgv.audioworkstation.core.audio.GainRange
import com.georgv.audioworkstation.core.audio.toMultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.toUiMessage
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.validation.NameValidationResult
import com.georgv.audioworkstation.core.validation.toProjectNameUiMessage
import com.georgv.audioworkstation.core.validation.toTrackNameUiMessage
import com.georgv.audioworkstation.core.validation.validateName
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.screens.projects.reorder.OptimisticTrackOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class ProjectUiState(
    val projectId: String? = null,
    val project: ProjectEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val selectedTrackIds: Set<String> = emptySet(),
    val playingTrackIds: Set<String> = emptySet(),
    val recordingTrackId: String? = null,
    val isRecordingStartup: Boolean = false
) {
    val isPlayEnabled: Boolean
        get() = selectedTrackIds.isNotEmpty()

    val isStopEnabled: Boolean
        get() = recordingTrackId != null || playingTrackIds.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val repo: ProjectRepository,
    private val audioController: AudioController,
    private val audioImportCoordinator: ProjectAudioImportCoordinator,
    private val recordingCoordinator: ProjectRecordingCoordinator,
) : ViewModel() {

    private val projectId = MutableStateFlow<String?>(null)

    private val selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)

    /**
     * Optimistic override for the on-screen track list.
     *
     * When non-null, this is the single source of truth that the UI renders (e.g. while the user
     * drags a track around). It gets cleared automatically once the DB observation reports the
     * same id ordering, after which the DB stream takes over again.
     */
    private val optimisticTracks = MutableStateFlow<List<TrackEntity>?>(null)
    private val recordingSession =
        RecordingSessionController(
            scope = viewModelScope,
            audioController = audioController,
            recordingCoordinator = recordingCoordinator,
        )
    /** Serializes [persistTrackOrderToDb] so overlapping drops cannot apply DB writes in the wrong order. */
    private val trackOrderPersistMutex = Mutex()

    private val resolvedProject = projectId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else repo.observeProject(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val projectTracks = projectId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repo.observeTracks(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val playbackSession = PlaybackSessionController(
        scope = viewModelScope,
        audioController = audioController,
        loadCurrentProject = { pid -> loadCurrentProject(pid) },
        currentProjectId = { projectId.value },
        visibleTracks = {
            visibleTracksWithRecordingOptimistic(
                projectTracks.value,
                optimisticTracks.value,
                recordingSession.optimisticRecordingTrack.value,
            )
        },
    )

    private val transportController = ProjectTransportController(
        audioController = audioController,
        playbackSession = playbackSession,
        recordingSession = recordingSession,
        finalizeRecordingTrackAfterSuccessfulEngineStop = { trackId -> finalizeRecordingTrack(trackId) },
    )

    val userMessages = messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            // Once the DB stream catches up to our optimistic order, drop the override so the
            // DB resumes being the single source of truth for subsequent updates.
            combine(projectTracks, optimisticTracks) { tracks, optimistic ->
                tracks to optimistic
            }.collect { (tracks, optimistic) ->
                if (optimistic != null && tracks.map { it.id } == optimistic.map { it.id }) {
                    optimisticTracks.value = null
                }
            }
        }
        viewModelScope.launch {
            combine(projectTracks, recordingSession.optimisticRecordingTrack) { tracks, optRecording ->
                tracks to optRecording
            }.collect { (tracks, optRecording) ->
                if (optRecording != null && tracks.any { it.id == optRecording.id }) {
                    recordingSession.clearOptimisticRecordingRow()
                }
            }
        }
    }

    private fun emitMessage(message: UiMessage) {
        messages.trySend(message)
    }

    private fun emitMessage(@StringRes resId: Int) {
        emitMessage(UiMessage(resId))
    }

    private suspend inline fun runDbAction(
        @StringRes errorResId: Int,
        crossinline action: suspend () -> Unit
    ): Boolean {
        return try {
            action()
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // Room/SQLite surfaces its failures as RuntimeException subclasses (e.g.
            // SQLiteConstraintException), so a single `Exception` net catches both the IO and
            // database failures these helpers wrap.
            emitMessage(errorResId)
            false
        }
    }

    /** Base list for UI: reorder override, then Room; append optimistic recording only if that id is absent. */
    private fun visibleTracksWithRecordingOptimistic(
        projectTracksList: List<TrackEntity>,
        optimisticOrder: List<TrackEntity>?,
        optimisticRecording: TrackEntity?,
    ): List<TrackEntity> {
        val base = optimisticOrder ?: projectTracksList
        val pending = optimisticRecording ?: return base
        return if (base.any { it.id == pending.id }) {
            base
        } else {
            base + pending
        }
    }

    private suspend inline fun runDbActionWithRollback(
        @StringRes errorResId: Int,
        rollback: () -> Unit,
        crossinline action: suspend () -> Unit
    ) {
        try {
            action()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            rollback()
            emitMessage(errorResId)
        }
    }

    val uiState: StateFlow<ProjectUiState> =
        combine(
            combine(projectId, resolvedProject) { pid, proj -> pid to proj },
            combine(projectTracks, optimisticTracks, recordingSession.optimisticRecordingTrack) {
                    projectTracksList,
                    optimisticOrder,
                    optimisticRecording,
                ->
                visibleTracksWithRecordingOptimistic(
                    projectTracksList,
                    optimisticOrder,
                    optimisticRecording,
                )
            },
            combine(selectedTrackIds, playbackSession.playingTrackIds) { selected, playing -> selected to playing },
            combine(recordingSession.recordingTrackId, recordingSession.recordingStartup) { recordingId, startup -> recordingId to startup }
        ) { pidProject, tracks, selPlay, recStartup ->
            val (pid, proj) = pidProject
            val (selected, playing) = selPlay
            val (recording, startup) = recStartup
            ProjectUiState(
                projectId = pid,
                project = proj,
                tracks = tracks,
                selectedTrackIds = selected,
                playingTrackIds = playing,
                recordingTrackId = recording,
                isRecordingStartup = startup
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUiState())

    /**
     * Wires repository/audio observation to [projectId] for this screen instance.
     *
     * Call once when [ProjectScreen] enters composition for a route argument. Switching projects should
     * navigate to a new `project/{projectId}` destination (new ViewModel), not repeatedly [bind] one VM.
     */
    suspend fun bind(projectId: String) {
        if (this.projectId.value != projectId) {
            transportController.resetPlaybackForProjectChange()
            optimisticTracks.value = null
            recordingSession.resetWhenBoundProjectChanges()
            selectedTrackIds.value = emptySet()
        }
        this.projectId.value = projectId
    }

    private suspend fun ensureProject(projectId: String, name: String): ProjectEntity? =
        try {
            repo.ensureProject(projectId, name)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            emitMessage(R.string.error_create_project_failed)
            null
        }

    private suspend fun loadCurrentProject(targetProjectId: String): ProjectEntity? {
        val loadedProject = resolvedProject.value?.takeIf { it.id == targetProjectId }
            ?: repo.observeProject(targetProjectId).first()
        if (loadedProject == null) {
            emitMessage(R.string.error_project_audio_unavailable)
        }
        return loadedProject
    }

    fun renameProject(newName: String) {
        val currentProject = uiState.value.project ?: return
        val normalizedName = when (val validation = validateName(newName)) {
            is NameValidationResult.Invalid -> {
                emitMessage(validation.error.toProjectNameUiMessage())
                return
            }
            is NameValidationResult.Valid -> validation.normalized
        }
        if (normalizedName == (currentProject.name ?: "").trim()) return

        val updatedProject = currentProject.copy(name = normalizedName)
        viewModelScope.launch {
            runDbAction(R.string.error_rename_project_failed) {
                repo.upsertProject(updatedProject)
            }
        }
    }

    fun deleteTrack(trackId: String) {
        if (recordingSession.recordingTrackId.value == trackId) {
            emitMessage(R.string.error_stop_recording_to_delete_track)
            return
        }
        if (playbackSession.playingTrackIds.value.contains(trackId)) {
            emitMessage(R.string.error_stop_playback_to_delete_track)
            return
        }

        val track = uiState.value.tracks.find { it.id == trackId } ?: return

        val previousSelected = selectedTrackIds.value
        selectedTrackIds.value = selectedTrackIds.value - trackId
        val remainingTracks = uiState.value.tracks
            .filter { it.id != trackId }
            .mapIndexed { i, t -> t.copy(position = i) }
        viewModelScope.launch {
            runDbActionWithRollback(
                errorResId = R.string.error_delete_track_failed,
                rollback = { selectedTrackIds.value = previousSelected }
            ) {
                repo.deleteTrack(track, remainingTracks)
            }
        }
    }

    fun renameTrack(trackId: String, newName: String) {
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
        val normalizedName = when (val validation = validateName(newName)) {
            is NameValidationResult.Invalid -> {
                emitMessage(validation.error.toTrackNameUiMessage())
                return
            }
            is NameValidationResult.Valid -> validation.normalized
        }
        if (normalizedName == (currentTrack.name ?: "").trim()) return

        val updatedTrack = currentTrack.copy(name = normalizedName)
        viewModelScope.launch {
            runDbAction(R.string.error_rename_track_failed) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun toggleTrackLoop(trackId: String) {
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
        val updatedTrack = currentTrack.copy(isLoop = !currentTrack.isLoop)
        viewModelScope.launch {
            runDbAction(R.string.error_loop_update_failed) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    /**
     * Live gain change while the user is interacting with the fader.
     * Pushes the value to the audio engine for immediate playback feedback
     * but does NOT touch the DB — that would re-emit the tracks list and
     * cause recomposition cascades that make the fader feel "jumpy".
     * The final value is persisted via [commitTrackGain] on release.
     */
    fun setTrackGain(trackId: String, gain: Float) {
        val contains = uiState.value.tracks.any { it.id == trackId }
        if (!contains) {
            return
        }
        if (playbackSession.playingTrackIds.value == setOf(trackId)) {
            audioController.setPlaybackGain(GainRange.toUnit(gain))
        }
    }

    /** Commit the latest gain value to the DB. Called when the user releases the fader. */
    fun commitTrackGain(trackId: String, gain: Float) {
        val currentTrack = uiState.value.tracks.find { it.id == trackId }
        if (currentTrack == null) {
            return
        }
        if (gain == currentTrack.gain) {
            return
        }

        val updatedTrack = currentTrack.copy(gain = gain)
        viewModelScope.launch {
            runDbAction(R.string.error_gain_update_failed) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun setTrackOrderSession(projectId: String, orderedTracks: List<TrackEntity>) {
        if (this.projectId.value != projectId) return
        if (orderedTracks.isEmpty()) return

        val next =
            OptimisticTrackOrder.applySession(
                liveTracks = uiState.value.tracks,
                proposedOrder = orderedTracks,
            )
                ?: return
        optimisticTracks.value = next
    }

    fun persistTrackOrderToDb(projectId: String) {
        if (this.projectId.value != projectId) return
        viewModelScope.launch {
            trackOrderPersistMutex.withLock {
                if (this@ProjectViewModel.projectId.value != projectId) return@withLock
                val list =
                    uiState.value.tracks.mapIndexed { index, track -> track.copy(position = index) }
                if (list.isEmpty()) return@withLock
                runDbActionWithRollback(
                    errorResId = R.string.error_save_track_order_failed,
                    rollback = { optimisticTracks.value = null }
                ) {
                    repo.updateTracks(list)
                }
            }
        }
    }

    fun toggleSelect(trackId: String) {
        val cur = selectedTrackIds.value
        selectedTrackIds.value = if (cur.contains(trackId)) cur - trackId else cur + trackId
    }

    fun importAudio(projectId: String, source: AudioImportSource, suggestedName: String?) {
        if (this.projectId.value != projectId) return
        viewModelScope.launch {
            if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) {
                emitMessage(R.string.error_stop_recording_to_import)
                return@launch
            }
            val currentProject = ensureProject(projectId, "New Project") ?: return@launch
            val visibleTrackCount = uiState.value.tracks.size

            when (
                val outcome =
                    audioImportCoordinator.run(
                        projectId = projectId,
                        project = currentProject,
                        visibleTrackCount = visibleTrackCount,
                        source = source,
                        suggestedName = suggestedName,
                    )
            ) {
                ProjectAudioImportOutcome.StorageUnavailable ->
                    emitMessage(R.string.error_import_storage_unavailable)
                is ProjectAudioImportOutcome.ImportRejected ->
                    emitMessage(outcome.failure.toUiMessage())
                is ProjectAudioImportOutcome.ReadyToPersist ->
                    runDbAction(R.string.error_save_imported_track_failed) {
                        repo.upsertTracks(listOf(outcome.importedTrack))
                    }
            }
        }
    }

    fun onRecordPressed(projectId: String, projectName: String = "New Project") {
        if (recordingSession.hasActiveRecordingTake()) {
            emitMessage(R.string.error_stop_recording_to_record)
            return
        }
        if (recordingSession.isStartupInFlight()) {
            return
        }

        recordingSession.armRecordingStartup()

        recordingSession.launchRecordPressed(
            projectId = projectId,
            projectName = projectName,
            ensureProject = { pid, name -> ensureProject(pid, name) },
            visibleTrackCount = { uiState.value.tracks.size },
            persistRecordingRow = { repo.upsertTracks(listOf(it)) },
            notifyEngineStartFailed = { emitMessage(R.string.error_recording_failed_to_start) },
            notifyPersistFailed = { emitMessage(R.string.error_create_recording_track_failed) },
        )
    }

    fun onPlayPressed() {
        val selected = selectedTrackIds.value

        viewModelScope.launch {
            if (playbackSession.isMarkedPlaying()) {
                emitMessage(R.string.error_stop_playback_first)
                return@launch
            }

            if (selected.isEmpty()) return@launch
            val currentProjectId = projectId.value ?: return@launch
            val currentProject = loadCurrentProject(currentProjectId) ?: return@launch
            val selectedPlayableTracks = uiState.value.tracks
                .filter { it.id in selected }
                .filter { it.wavFilePath.isNotBlank() }
            val playbackSpec = currentProject.toMultiPlaybackSpec(selectedPlayableTracks)
            if (playbackSpec == null) {
                emitMessage(playbackStartRejectedMessage(selectedPlayableTracks.size))
                return@launch
            }
            if (!audioController.startPlayback(playbackSpec)) {
                emitMessage(R.string.error_playback_failed_to_start)
                return@launch
            }
            playbackSession.markPlayingAndStartCompletionMonitor(playbackSpec.lanes.mapTo(LinkedHashSet()) { it.trackId })
        }
    }

    /** Stops all active transport; sequencing is [ProjectTransportController.stopAll]. */
    fun onStopPressed() {
        transportController.stopAll()
    }

    override fun onCleared() {
        onStopPressed()
        // Release the persistent Oboe stream and streaming I/O thread once the
        // project screen goes away. Without this we'd keep the audio device
        // open for the lifetime of the process even after the user navigates
        // out, which is wasteful on battery.
        audioController.release()
        super.onCleared()
    }

    private fun finalizeRecordingTrack(trackId: String) {
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
        val finalizedTrack = recordingCoordinator.finalizedTrackAfterStop(currentTrack)

        viewModelScope.launch {
            runDbAction(R.string.error_recording_metadata_failed) {
                repo.upsertTrack(finalizedTrack)
            }
        }
    }

    @StringRes
    private fun playbackStartRejectedMessage(playableTrackCount: Int): Int =
        if (playableTrackCount == 0) {
            R.string.error_no_audio_for_selected_tracks
        } else {
            R.string.error_playback_failed_to_start
        }

}
