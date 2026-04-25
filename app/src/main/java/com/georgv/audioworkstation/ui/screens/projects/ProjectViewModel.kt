package com.georgv.audioworkstation.ui.screens.projects

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.AudioImportResult
import com.georgv.audioworkstation.core.audio.AudioImportSource
import com.georgv.audioworkstation.core.audio.AudioImportTarget
import com.georgv.audioworkstation.core.audio.AudioImporter
import com.georgv.audioworkstation.core.audio.GainRange
import com.georgv.audioworkstation.core.audio.toPlaybackSpec
import com.georgv.audioworkstation.core.audio.toRecordingSpec
import com.georgv.audioworkstation.core.audio.toUiMessage
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.validation.NameValidationResult
import com.georgv.audioworkstation.core.validation.toProjectNameUiMessage
import com.georgv.audioworkstation.core.validation.toTrackNameUiMessage
import com.georgv.audioworkstation.core.validation.validateName
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.max
import javax.inject.Inject

data class ProjectUiState(
    val projectId: String? = null,
    val project: ProjectEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val selectedTrackIds: Set<String> = emptySet(),
    val playingTrackIds: Set<String> = emptySet(),
    val recordingTrackId: String? = null
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
    private val audioImporter: AudioImporter,
    private val audioFilePathProvider: AudioFilePathProvider
) : ViewModel() {

    private val projectId = MutableStateFlow<String?>(null)

    private val playingTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val recordingTrackId = MutableStateFlow<String?>(null)
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)

    /**
     * Optimistic override for the on-screen track list.
     *
     * When non-null, this is the single source of truth that the UI renders (e.g. while the user
     * drags a track around). It gets cleared automatically once the DB observation reports the
     * same id ordering, after which the DB stream takes over again.
     */
    private val optimisticTracks = MutableStateFlow<List<TrackEntity>?>(null)
    private var playbackMonitorJob: Job? = null

    private val project = projectId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else repo.observeProject(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val projectTracks = projectId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repo.observeTracks(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
            combine(projectId, project) { pid, project -> pid to project },
            combine(projectTracks, optimisticTracks) { tracks, optimistic ->
                optimistic ?: tracks
            },
            selectedTrackIds,
            playingTrackIds,
            recordingTrackId
        ) { pidProject, tracks, selected, playing, recording ->
            val (pid, project) = pidProject
            ProjectUiState(
                projectId = pid,
                project = project,
                tracks = tracks,
                selectedTrackIds = selected,
                playingTrackIds = playing,
                recordingTrackId = recording
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUiState())

    suspend fun bind(projectId: String) {
        if (this.projectId.value != projectId) {
            playbackMonitorJob?.cancel()
            optimisticTracks.value = null
            selectedTrackIds.value = emptySet()
            playingTrackIds.value = emptySet()
            recordingTrackId.value = null
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

    private suspend fun loadCurrentProject(projectId: String): ProjectEntity? {
        val loadedProject = project.value?.takeIf { it.id == projectId }
            ?: repo.observeProject(projectId).first()
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
        if (recordingTrackId.value == trackId) {
            emitMessage(R.string.error_stop_recording_to_delete_track)
            return
        }
        if (playingTrackIds.value.contains(trackId)) {
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
        if (uiState.value.tracks.none { it.id == trackId }) return
        if (playingTrackIds.value.contains(trackId)) {
            audioController.setPlaybackGain(GainRange.toUnit(gain))
        }
    }

    /** Commit the latest gain value to the DB. Called when the user releases the fader. */
    fun commitTrackGain(trackId: String, gain: Float) {
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
        if (gain == currentTrack.gain) return

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
        val live = uiState.value.tracks.associateBy { it.id }
        val merged = orderedTracks
            .filter { it.id in live }
            .mapIndexed { index, row -> live.getValue(row.id).copy(position = index) }
        if (merged.isEmpty()) return
        val presentIds = merged.map { it.id }.toSet()
        val trailing = uiState.value.tracks
            .filter { it.id !in presentIds }
            .sortedBy { it.position }
            .mapIndexed { i, t -> t.copy(position = merged.size + i) }
        val next = merged + trailing
        if (next.map { it.id } == uiState.value.tracks.map { it.id }) return
        optimisticTracks.value = next
    }

    fun persistTrackOrderToDb(projectId: String) {
        if (this.projectId.value != projectId) return
        val list = uiState.value.tracks.mapIndexed { index, track -> track.copy(position = index) }
        if (list.isEmpty()) return
        viewModelScope.launch {
            runDbActionWithRollback(
                errorResId = R.string.error_save_track_order_failed,
                rollback = { optimisticTracks.value = null }
            ) {
                repo.updateTracks(list)
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
            if (recordingTrackId.value != null) {
                emitMessage(R.string.error_stop_recording_to_import)
                return@launch
            }
            val currentProject = ensureProject(projectId, "New Project") ?: return@launch

            val existingCount = uiState.value.tracks.size
            val pendingTrack = repo.appendTrackToProject(projectId, "Take ${existingCount + 1}")
            val destinationPath = audioFilePathProvider.trackOutputPath(projectId, pendingTrack.id)
            if (destinationPath == null) {
                emitMessage(R.string.error_import_storage_unavailable)
                return@launch
            }

            val target = AudioImportTarget(
                sampleRate = currentProject.sampleRate,
                fileBitDepth = currentProject.fileBitDepth,
                channelMode = pendingTrack.channelMode
            )
            val result = audioImporter.import(source, destinationPath, target)
            when (result) {
                is AudioImportResult.Success -> {
                    val importedName = suggestedName
                        ?.let { validateName(it) as? NameValidationResult.Valid }
                        ?.normalized
                        ?: "Take ${existingCount + 1} (imported)"
                    val importedTrack = pendingTrack.copy(
                        name = importedName,
                        wavFilePath = destinationPath,
                        duration = result.durationMs,
                        channelMode = result.channelMode,
                        isImported = true
                    )
                    runDbAction(R.string.error_save_imported_track_failed) {
                        repo.upsertTracks(listOf(importedTrack))
                    }
                }
                is AudioImportResult.Failure -> {
                    emitMessage(result.toUiMessage())
                }
            }
        }
    }

    fun onRecordPressed(projectId: String, projectName: String = "New Project") {
        viewModelScope.launch {
            if (recordingTrackId.value != null) {
                emitMessage(R.string.error_stop_recording_to_record)
                return@launch
            }

            val currentProject = ensureProject(projectId, projectName) ?: return@launch

            val pendingTrack = repo.appendTrackToProject(
                projectId = projectId,
                name = "Take ${uiState.value.tracks.size + 1}"
            )
            val outputPath = audioController.startRecording(currentProject.toRecordingSpec(pendingTrack))
            if (outputPath == null) {
                emitMessage(R.string.error_recording_failed_to_start)
                return@launch
            }
            val newTrack = pendingTrack.copy(
                wavFilePath = outputPath,
                timeStampStart = System.currentTimeMillis(),
                isRecording = true
            )

            runDbActionWithRollback(
                errorResId = R.string.error_create_recording_track_failed,
                rollback = { audioController.stopRecording() }
            ) {
                repo.upsertTracks(listOf(newTrack))
                recordingTrackId.value = newTrack.id
            }
        }
    }

    fun onPlayPressed() {
        val selected = selectedTrackIds.value

        viewModelScope.launch {
            if (playingTrackIds.value.isNotEmpty()) {
                emitMessage(R.string.error_stop_playback_first)
                return@launch
            }

            if (selected.isEmpty()) return@launch
            val currentProjectId = projectId.value ?: return@launch
            val currentProject = loadCurrentProject(currentProjectId) ?: return@launch
            val selectedTracks = uiState.value.tracks.filter { it.id in selected }
            if (selectedTracks.size != 1) {
                emitMessage(R.string.error_single_track_playback_only)
                return@launch
            }
            val selectedTrack = selectedTracks.single()
            val playbackSpec = currentProject.toPlaybackSpec(selectedTrack)
            if (playbackSpec == null) {
                emitMessage(R.string.error_no_audio_for_selected_tracks)
                return@launch
            }
            if (!audioController.startPlayback(playbackSpec)) {
                emitMessage(R.string.error_playback_failed_to_start)
                return@launch
            }
            playingTrackIds.value = setOf(selectedTrack.id)
            startPlaybackMonitor(selectedTrack.id)
        }
    }

    fun onStopPressed() {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = null
        val activeRecordingTrackId = recordingTrackId.value
        if (activeRecordingTrackId != null && audioController.stopRecording()) {
            finalizeRecordingTrack(activeRecordingTrackId)
        }
        if (playingTrackIds.value.isNotEmpty()) {
            audioController.stopPlayback()
        }
        recordingTrackId.value = null
        playingTrackIds.value = emptySet()
    }

    override fun onCleared() {
        onStopPressed()
        super.onCleared()
    }

    private fun startPlaybackMonitor(trackId: String) {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = viewModelScope.launch {
            // The engine reports completion via [AudioController.playbackState]; we wait for it
            // to transition to false and then either restart (loop) or clear the playing set.
            while (true) {
                audioController.playbackState.filter { !it }.first()
                if (playingTrackIds.value != setOf(trackId)) break
                if (!shouldRestartLoopPlayback(trackId)) {
                    playingTrackIds.value = emptySet()
                    break
                }
            }
            playbackMonitorJob = null
        }
    }

    private suspend fun shouldRestartLoopPlayback(trackId: String): Boolean {
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return false
        if (!currentTrack.isLoop) return false
        val currentProjectId = projectId.value ?: return false
        val currentProject = loadCurrentProject(currentProjectId) ?: return false
        val spec = currentProject.toPlaybackSpec(currentTrack) ?: return false
        return audioController.startPlayback(spec)
    }

    private fun finalizeRecordingTrack(trackId: String) {
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
        val stopTimestamp = System.currentTimeMillis()
        val duration = max(0L, stopTimestamp - currentTrack.timeStampStart)
        val finalizedTrack = currentTrack.copy(
            timeStampStop = stopTimestamp,
            duration = duration,
            isRecording = false
        )

        viewModelScope.launch {
            runDbAction(R.string.error_recording_metadata_failed) {
                repo.upsertTrack(finalizedTrack)
            }
        }
    }
}
