package com.georgv.audioworkstation.data.repository

import com.georgv.audioworkstation.core.audio.ProjectFileStore
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import android.os.SystemClock
import com.georgv.audioworkstation.ui.diagnostics.QuickRecordDiagnostics
import com.georgv.audioworkstation.ui.screens.library.LibraryDiagnostics
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Singleton
class ProjectRepository @Inject constructor(
    private val dao: ProjectDao,
    private val fileStore: ProjectFileStore,
) {
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val projectsFirstEmissionLogged = AtomicBoolean(false)

    /** Eagerly cached project list — revisiting Library gets last known data immediately. */
    val projectsState: StateFlow<List<ProjectEntity>> =
        dao.observeProjects()
            .onEach { projects ->
                if (QuickRecordDiagnostics.loggingEnabled && QuickRecordDiagnostics.quickNavigationActive) {
                    val emissionStartMs = SystemClock.uptimeMillis()
                    QuickRecordDiagnostics.logStepStart(
                        "ProjectRepository projects emission",
                        detail = "count=${projects.size} duringQuickNav=true",
                    )
                    QuickRecordDiagnostics.logStepEnd(
                        "ProjectRepository projects emission",
                        emissionStartMs,
                        detail = "count=${projects.size} thread=${QuickRecordDiagnostics.threadLabel()} " +
                            "isMain=${QuickRecordDiagnostics.isMainThread()} duringQuickNav=true",
                    )
                }
                if (projectsFirstEmissionLogged.compareAndSet(false, true)) {
                    LibraryDiagnostics.logProjectsFirstEmission(projects.size)
                }
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
        if (QuickRecordDiagnostics.isActiveFor(project.id)) {
            val dbStartMs = SystemClock.uptimeMillis()
            QuickRecordDiagnostics.logDbWriteStart("upsertProject", project.id)
            dao.upsertProject(project)
            QuickRecordDiagnostics.logDbWriteEnd("upsertProject", dbStartMs, project.id)
        } else {
            dao.upsertProject(project)
        }
    }

    fun observeTracks(projectId: String): Flow<List<TrackEntity>> =
        dao.observeTracks(projectId)
            .onEach { tracks ->
                if (QuickRecordDiagnostics.isActiveFor(projectId)) {
                    QuickRecordDiagnostics.traceSection("QuickProjectTracksEmission", projectId) {
                        QuickRecordDiagnostics.log(
                            "ProjectRepository tracks emission",
                            "projectId=$projectId count=${tracks.size} " +
                                "thread=${QuickRecordDiagnostics.threadLabel()} " +
                                "isMain=${QuickRecordDiagnostics.isMainThread()} duringQuickNav=true",
                        )
                    }
                }
            }

    suspend fun upsertTrack(track: TrackEntity) {
        if (QuickRecordDiagnostics.isActiveFor(track.projectId)) {
            val dbStartMs = SystemClock.uptimeMillis()
            QuickRecordDiagnostics.logDbWriteStart("upsertTrack trackId=${track.id}", track.projectId)
            dao.upsertTrack(track)
            QuickRecordDiagnostics.logDbWriteEnd("upsertTrack trackId=${track.id}", dbStartMs, track.projectId)
        } else {
            dao.upsertTrack(track)
        }
    }

    suspend fun upsertTracks(tracks: List<TrackEntity>) {
        val projectId = tracks.firstOrNull()?.projectId
        if (projectId != null && QuickRecordDiagnostics.isActiveFor(projectId)) {
            val dbStartMs = SystemClock.uptimeMillis()
            QuickRecordDiagnostics.logDbWriteStart("upsertTracks count=${tracks.size}", projectId)
            dao.upsertTracks(tracks)
            QuickRecordDiagnostics.logDbWriteEnd("upsertTracks count=${tracks.size}", dbStartMs, projectId)
        } else {
            dao.upsertTracks(tracks)
        }
    }

    suspend fun updateTracks(tracks: List<TrackEntity>) = dao.updateTracks(tracks)

    /**
     * Creates the project row if it does not exist yet, then returns the latest version of it.
     * Replaces the legacy "show error and return null" pattern in the ViewModels.
     */
    suspend fun ensureProject(projectId: String, defaultName: String): ProjectEntity =
        QuickRecordDiagnostics.traceSection("QuickProjectCreate", projectId) {
            val createStartMs = SystemClock.uptimeMillis()
            QuickRecordDiagnostics.logStepStart("quick project creation", projectId)
            if (!dao.projectExists(projectId)) {
                val dbStartMs = SystemClock.uptimeMillis()
                if (QuickRecordDiagnostics.isActiveFor(projectId)) {
                    QuickRecordDiagnostics.logDbWriteStart("ensureProject", projectId)
                }
                dao.upsertProject(ProjectEntity(id = projectId, name = defaultName))
                if (QuickRecordDiagnostics.isActiveFor(projectId)) {
                    QuickRecordDiagnostics.logDbWriteEnd("ensureProject", dbStartMs, projectId)
                }
            }
            val project =
                dao.observeProject(projectId).first()
                    ?: error("Project $projectId disappeared right after upsert.")
            QuickRecordDiagnostics.logStepEnd(
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
        QuickRecordDiagnostics.traceSection("QuickInitialTrackCreate", projectId) {
            val createStartMs = SystemClock.uptimeMillis()
            QuickRecordDiagnostics.logStepStart("initial track creation", projectId)
            val readStartMs = SystemClock.uptimeMillis()
            val existing = dao.observeTracks(projectId).first()
            QuickRecordDiagnostics.logStepEnd(
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
            QuickRecordDiagnostics.logStepEnd(
                "initial track creation",
                createStartMs,
                projectId,
                "trackId=${track.id} name=$name",
            )
            track
        }
}
