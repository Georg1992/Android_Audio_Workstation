package com.georgv.audioworkstation.ui.screens.projects


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ProjectUiState(
    val projectId: String? = null,
    val project: ProjectEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val selectedTrackIds: Set<String> = emptySet(),
    val playingTrackIds: Set<String> = emptySet(),
    val recordingTrackId: String? = null
) {
    val transportState: TransportState
        get() = when {
            recordingTrackId != null && selectedTrackIds.isNotEmpty() -> TransportState.Overdub
            recordingTrackId != null -> TransportState.Recording
            playingTrackIds.isNotEmpty() -> TransportState.Playing
            else -> TransportState.Idle
        }

    val isPlayEnabled: Boolean
        get() = selectedTrackIds.isNotEmpty()

    val isStopEnabled: Boolean
        get() = recordingTrackId != null || playingTrackIds.isNotEmpty()
}


@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val repo: ProjectRepository
) : ViewModel() {

    private val projectId = MutableStateFlow<String?>(null)
    private var boundProjectId: String? = null

    private val playingTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val recordingTrackId = MutableStateFlow<String?>(null)

    /** Tracks for the open project; loaded from DB in [bind] only, then owned here until another bind. */
    private val tracksSession = MutableStateFlow<List<TrackEntity>>(emptyList())

    val uiState: StateFlow<ProjectUiState> =
        combine(
            combine(projectId, repo.observeProjects()) { pid, allProjects -> pid to allProjects },
            tracksSession,
            selectedTrackIds,
            playingTrackIds,
            recordingTrackId
        ) { pidProjects, tracks, selected, playing, recording ->
            val (pid, allProjects) = pidProjects
            val project = pid?.let { id -> allProjects.firstOrNull { it.id == id } }
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
        if (boundProjectId == projectId) return
        boundProjectId = projectId
        this.projectId.value = projectId
        tracksSession.value = repo.observeTracks(projectId).first().sortedBy { it.position }
    }

    suspend fun ensureProjectExists(projectId: String, name: String) {
        if (repo.getProjectWithTracks(projectId) == null) {
            repo.upsertProject(ProjectEntity(id = projectId, name = name))
        }
    }

    fun addTrack(projectId: String, name: String? = null) {
        if (this.projectId.value != projectId) return
        viewModelScope.launch {
            ensureProjectExists(projectId, "New Project")
            val existingCount = tracksSession.value.size
            val finalName = name ?: "Take ${existingCount + 1}"

            val track = TrackEntity(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                position = existingCount,
                name = finalName,
                wavFilePath = ""
            )
            repo.upsertTracks(listOf(track))
            tracksSession.value = tracksSession.value + track
        }
    }

    fun toggleSelect(trackId: String) {
        val cur = selectedTrackIds.value
        selectedTrackIds.value = if (cur.contains(trackId)) cur - trackId else cur + trackId
    }

    fun onRecordPressed(projectId: String) {
        viewModelScope.launch {
            if (recordingTrackId.value != null) {
                recordingTrackId.value = null
                return@launch
            }

            ensureProjectExists(projectId, "New Project")

            val existingCount = tracksSession.value.size
            val newTrack = TrackEntity(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                position = existingCount,
                name = "Take ${existingCount + 1}",
                wavFilePath = ""
            )
            repo.upsertTracks(listOf(newTrack))
            tracksSession.value = tracksSession.value + newTrack
            recordingTrackId.value = newTrack.id
        }
    }

    fun onPlayPressed() {
        val selected = selectedTrackIds.value

        viewModelScope.launch {
            if (playingTrackIds.value.isNotEmpty()) {
                playingTrackIds.value = emptySet()
                return@launch
            }

            if (selected.isEmpty()) return@launch
            playingTrackIds.value = selected
        }
    }

    fun onStopPressed() {
        recordingTrackId.value = null
        playingTrackIds.value = emptySet()
    }

    fun deleteProject() {
        val id = projectId.value ?: return
        viewModelScope.launch {
            repo.deleteProject(id)
        }
    }

    /** In-memory order only (e.g. while dragging past threshold). DB unchanged. */
    fun setTrackOrderSession(projectId: String, orderedTracks: List<TrackEntity>) {
        if (this.projectId.value != projectId) return
        if (orderedTracks.isEmpty()) return
        val sessionById = tracksSession.value.associateBy { it.id }
        val merged = orderedTracks
            .filter { it.id in sessionById }
            .mapIndexed { index, row -> sessionById.getValue(row.id).copy(position = index) }
        if (merged.isEmpty()) return
        val presentIds = merged.map { it.id }.toSet()
        val trailing = tracksSession.value
            .filter { it.id !in presentIds }
            .sortedBy { it.position }
            .mapIndexed { i, t -> t.copy(position = merged.size + i) }
        tracksSession.value = merged + trailing
    }

    /** Persist current session order to DB (call on drop). */
    fun persistTrackOrderToDb(projectId: String) {
        if (this.projectId.value != projectId) return
        val list = tracksSession.value
        if (list.isEmpty()) return
        viewModelScope.launch {
            repo.updateTracks(list)
        }
    }

    fun deleteTrack(trackId: String) {
        selectedTrackIds.value = selectedTrackIds.value - trackId
        if (recordingTrackId.value == trackId) recordingTrackId.value = null
        if (playingTrackIds.value.contains(trackId)) {
            playingTrackIds.value = playingTrackIds.value - trackId
        }
        val newList = tracksSession.value
            .filter { it.id != trackId }
            .mapIndexed { i, t -> t.copy(position = i) }
        tracksSession.value = newList
        viewModelScope.launch {
            if (newList.isEmpty()) {
                repo.deleteTrack(trackId)
            } else {
                repo.deleteTrackAndUpdatePositions(trackId, newList)
            }
        }
    }
}
