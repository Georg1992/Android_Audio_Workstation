package com.georgv.audioworkstation.data.repository

import android.os.SystemClock
import com.georgv.audioworkstation.core.audio.ProjectFileStore
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@Singleton
class ProjectRepository @Inject constructor(
    private val dao: ProjectDao,
    private val fileStore: ProjectFileStore,
    private val diagnostics: ProjectRepositoryDiagnostics = ProjectRepositoryDiagnostics.None,
) {
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Eagerly cached project list — revisiting Library gets last known data immediately. */
    val projectsState: StateFlow<List<ProjectEntity>> =
        dao.observeProjects()
            .onEach { projects ->
                diagnostics.onProjectsCachedEmission(projects.size)
            }
            .stateIn(cacheScope, SharingStarted.Eagerly, emptyList())

    /** True after Room emits the first project list to [projectsState]. */
    val projectsReady: StateFlow<Boolean> =
        dao.observeProjects()
            .map { true }
            .stateIn(cacheScope, SharingStarted.Eagerly, false)

    fun observeProjects(): Flow<List<ProjectEntity>> = projectsState

    fun observeProject(projectId: String): Flow<ProjectEntity?> = dao.observeProject(projectId)

    suspend fun projectExists(projectId: String): Boolean = dao.projectExists(projectId)

    suspend fun upsertProject(project: ProjectEntity) {
        diagnostics.dbWriteWhenActive("upsertProject", project.id) {
            dao.upsertProject(project)
        }
    }

    fun observeTracks(projectId: String): Flow<List<TrackEntity>> =
        dao.observeTracks(projectId)
            .onEach { tracks ->
                diagnostics.onTracksObserved(projectId, tracks.size)
            }

    suspend fun upsertTrack(track: TrackEntity) {
        diagnostics.dbWriteWhenActive("upsertTrack trackId=${track.id}", track.projectId) {
            dao.upsertTrack(track)
        }
    }

    suspend fun upsertTracks(tracks: List<TrackEntity>) {
        val projectId = tracks.firstOrNull()?.projectId
        diagnostics.dbWriteWhenActive("upsertTracks count=${tracks.size}", projectId) {
            dao.upsertTracks(tracks)
        }
    }

    suspend fun updateTracks(tracks: List<TrackEntity>) = dao.updateTracks(tracks)

    /**
     * Creates the project row if it does not exist yet, then returns the latest version of it.
     * Replaces the legacy "show error and return null" pattern in the ViewModels.
     */
    suspend fun ensureProject(projectId: String, defaultName: String): ProjectEntity =
        diagnostics.traceQuickRecordSection("QuickProjectCreate", projectId) {
            val createStartMs = SystemClock.uptimeMillis()
            diagnostics.logQuickRecordStepStart("quick project creation", projectId)
            if (!dao.projectExists(projectId)) {
                diagnostics.dbWriteWhenActive("ensureProject", projectId) {
                    dao.upsertProject(ProjectEntity(id = projectId, name = defaultName))
                }
            }
            val project =
                dao.observeProject(projectId).first()
                    ?: error("Project $projectId disappeared right after upsert.")
            diagnostics.logQuickRecordStepEnd(
                "quick project creation",
                createStartMs,
                projectId,
                "name=${project.name}",
            )
            project
        }

    /**
     * Deletes the project row (cascading track rows via the FK) and the on-disk audio folder.
     * The DAO row is deleted first so the UI sees the project go away immediately even if file
     * cleanup is slow or fails (in which case the orphaned files are harmless).
     */
    suspend fun deleteProject(projectId: String) {
        dao.deleteProject(projectId)
        fileStore.deleteProjectFolder(projectId)
    }

    /**
     * Deletes a single track: removes its audio file, then either removes it from the DB
     * outright (if the project will have no tracks left) or removes it and updates the
     * remaining tracks' positions in a single transaction.
     *
     * The audio file is deleted first so a successful DAO update never leaves an orphan file
     * behind; if the DAO call fails the (already-deleted) file is the price of consistency
     * and the rollback in the ViewModel re-emits the original list.
     */
    suspend fun deleteTrack(track: TrackEntity, remainingTracks: List<TrackEntity>) {
        fileStore.deleteTrackFile(track)
        if (remainingTracks.isEmpty()) {
            dao.deleteTrack(track.id)
        } else {
            dao.deleteTrackAndUpdatePositions(track.id, remainingTracks)
        }
    }

    /**
     * Allocates a fresh [TrackEntity] for the given project at the next available position.
     * The caller is responsible for filling in the audio path / metadata before persisting via
     * [upsertTracks].
     */
    suspend fun appendTrackToProject(projectId: String, name: String): TrackEntity =
        diagnostics.traceQuickRecordSection("QuickInitialTrackCreate", projectId) {
            val createStartMs = SystemClock.uptimeMillis()
            diagnostics.logQuickRecordStepStart("initial track creation", projectId)
            val readStartMs = SystemClock.uptimeMillis()
            val existing = dao.observeTracks(projectId).first()
            diagnostics.logQuickRecordStepEnd(
                "initial track read for allocation",
                readStartMs,
                projectId,
                "existingCount=${existing.size}",
            )
            val track =
                TrackEntity(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    position = existing.size,
                    name = name,
                    wavFilePath = "",
                )
            diagnostics.logQuickRecordStepEnd(
                "initial track creation",
                createStartMs,
                projectId,
                "trackId=${track.id} name=$name",
            )
            track
        }
}
