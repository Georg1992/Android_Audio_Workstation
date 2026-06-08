package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.AudioIoScope
import com.georgv.audioworkstation.core.coroutines.withIo
import com.georgv.audioworkstation.core.track.selectedPlayableTracks
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.components.mixdownTimelineEndMs
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Cross-screen mixdown orchestration keyed by project id.
 *
 * Runs on [AudioIoScope] (process lifetime), not on any screen [androidx.lifecycle.ViewModel] scope.
 * UI observes [mixdownByProjectIdState] from here or via [com.georgv.audioworkstation.ui.mixdown.ProjectMixdownViewModel].
 */
@Singleton
class ProjectMixdownCoordinator @Inject constructor(
    private val repo: ProjectRepository,
    private val audioFilePathProvider: AudioFilePathProvider,
    private val offlineMixdownRenderer: ProjectOfflineMixdownRenderer,
    private val mixdownOutputValidator: MixdownOutputValidator,
    private val audioController: AudioController,
    private val dispatchers: AppDispatchers,
    private val audioIoScope: AudioIoScope,
) {
    private val mixdownByProjectId = MutableStateFlow<Map<String, ProjectMixdownState>>(emptyMap())
    private val activeJobs = mutableMapOf<String, Job>()

    val mixdownByProjectIdState: StateFlow<Map<String, ProjectMixdownState>> =
        mixdownByProjectId.asStateFlow()

    fun mixdownState(projectId: String): ProjectMixdownState =
        mixdownByProjectId.value[projectId] ?: ProjectMixdownState()

    fun isMixing(projectId: String): Boolean =
        mixdownByProjectId.value[projectId]?.isMixing == true

    /**
     * Reconcile on-disk mixdown files for known projects (e.g. after app restart).
     * Invalid files are ignored and never exposed as preview paths.
     */
    suspend fun refreshKnownMixdownPaths(projectIds: Collection<String>) {
        withIo(dispatchers, "mixdown.refreshPaths") {
            mixdownByProjectId.update { current ->
                val updated = current.toMutableMap()
                projectIds.forEach { projectId ->
                    val existing = updated[projectId]
                    if (existing?.isMixing == true) return@forEach

                    val path = audioFilePathProvider.mixdownOutputPath(projectId) ?: return@forEach
                    val project = repo.observeProject(projectId).first() ?: return@forEach
                    if (
                        mixdownOutputValidator.isValidMixdownFile(
                            outputPath = path,
                            projectSampleRateHz = project.sampleRate,
                            expectedDurationMs = null,
                        )
                    ) {
                        updated[projectId] =
                            ProjectMixdownState(
                                mixdownWavPath = path,
                                progress = 1f,
                            )
                    } else if (existing?.mixdownWavPath == path) {
                        updated[projectId] = existing.copy(mixdownWavPath = null, progress = 0f)
                    }
                }
                updated
            }
        }
    }

    /**
     * @return false when a mix is already running for [projectId].
     */
    fun startMixdown(projectId: String, selectedTrackIds: Set<String>): Boolean {
        if (selectedTrackIds.isEmpty()) return false
        if (isMixing(projectId) || activeJobs.containsKey(projectId)) return false
        updateProjectState(projectId) { previous ->
            previous.copy(
                isMixing = true,
                progress = 0f,
                errorMessageResId = null,
            )
        }
        activeJobs.remove(projectId)?.cancel()
        audioController.cancelOfflineMixdown()
        val job =
            audioIoScope.scope.launch(dispatchers.io) {
                runMixdown(projectId, selectedTrackIds)
            }
        activeJobs[projectId] = job
        job.invokeOnCompletion { activeJobs.remove(projectId) }
        return true
    }

    fun clearProject(projectId: String) {
        activeJobs.remove(projectId)?.cancel()
        audioController.cancelOfflineMixdown()
        mixdownByProjectId.update { current -> current - projectId }
    }

    internal suspend fun runMixdown(projectId: String, selectedTrackIds: Set<String>) {
        try {
            val outputPath =
                withIo(dispatchers, "mixdown.resolveOutputPath") {
                    audioFilePathProvider.mixdownOutputPath(projectId)
                }
            if (outputPath == null) {
                failMixdown(projectId, R.string.error_mixdown_not_available)
                return
            }

            val project =
                withIo(dispatchers, "mixdown.loadProject") {
                    repo.observeProject(projectId).first()
                }
            if (project == null) {
                failMixdown(projectId, R.string.error_mixdown_failed)
                return
            }

            val tracks =
                withIo(dispatchers, "mixdown.loadTracks") {
                    repo.observeTracks(projectId).first()
                }

            val mixTracks = selectedPlayableTracks(tracks, selectedTrackIds)
            if (mixTracks.isEmpty()) {
                failMixdown(
                    projectId,
                    if (selectedTrackIds.isEmpty()) {
                        R.string.error_mixdown_no_playable_tracks
                    } else {
                        R.string.error_no_audio_for_selected_tracks
                    },
                )
                return
            }

            val result =
                offlineMixdownRenderer.render(
                    project = project,
                    tracks = tracks,
                    selectedTrackIds = selectedTrackIds,
                    outputPath = outputPath,
                    onProgress = { progress ->
                        updateProjectState(projectId) { current ->
                            current.copy(progress = progress.coerceIn(0f, 1f))
                        }
                    },
                )
            handleMixdownRenderResult(
                projectId = projectId,
                outputPath = outputPath,
                project = project,
                tracks = tracks,
                selectedTrackIds = selectedTrackIds,
                result = result,
            )
        } catch (cancel: CancellationException) {
            handleMixdownCancellation(projectId)
            throw cancel
        } catch (error: Exception) {
            logWarning(TAG, "runMixdown failed: $projectId", error)
            failMixdown(projectId, R.string.error_mixdown_failed)
        }
    }

    private suspend fun handleMixdownRenderResult(
        projectId: String,
        outputPath: String,
        project: ProjectEntity,
        tracks: List<TrackEntity>,
        selectedTrackIds: Set<String>,
        result: OfflineMixdownResult,
    ) {
        when (result) {
            is OfflineMixdownResult.Success ->
                acceptMixdownOutput(
                    projectId = projectId,
                    outputPath = result.outputPath,
                    project = project,
                    tracks = tracks,
                    selectedTrackIds = selectedTrackIds,
                )

            OfflineMixdownResult.NoPlayableTracks ->
                failMixdown(projectId, R.string.error_mixdown_no_playable_tracks)

            OfflineMixdownResult.Cancelled ->
                failMixdown(projectId, R.string.error_mixdown_failed)

            OfflineMixdownResult.WriteFailed ->
                rejectMixdownOutput(projectId, outputPath, R.string.error_mixdown_failed)
        }
    }

    private fun handleMixdownCancellation(projectId: String) {
        audioController.cancelOfflineMixdown()
        mixdownByProjectId.update { current ->
            val existing = current[projectId] ?: return@update current - projectId
            if (existing.isMixing) {
                current + (projectId to existing.copy(isMixing = false))
            } else {
                current - projectId
            }
        }
    }

    private suspend fun acceptMixdownOutput(
        projectId: String,
        outputPath: String,
        project: ProjectEntity,
        tracks: List<TrackEntity>,
        selectedTrackIds: Set<String>,
    ) {
        val expectedDurationMs = mixdownTimelineEndMs(tracks, selectedTrackIds)
        val valid =
            withIo(dispatchers, "mixdown.validateOutput") {
                mixdownOutputValidator.isValidMixdownFile(
                    outputPath = outputPath,
                    projectSampleRateHz = project.sampleRate,
                    expectedDurationMs = expectedDurationMs,
                )
            }
        if (!valid) {
            rejectMixdownOutput(projectId, outputPath, R.string.error_mixdown_failed)
            return
        }
        updateProjectState(projectId) { previous ->
            previous.copy(
                isMixing = false,
                progress = 1f,
                mixdownWavPath = outputPath,
                errorMessageResId = null,
            )
        }
    }

    private suspend fun rejectMixdownOutput(
        projectId: String,
        outputPath: String,
        messageResId: Int,
    ) {
        withIo(dispatchers, "mixdown.deleteInvalidOutput") {
            File(outputPath).delete()
        }
        failMixdown(projectId, messageResId)
    }

    private fun failMixdown(projectId: String, messageResId: Int) {
        updateProjectState(projectId) { previous ->
            previous.copy(
                isMixing = false,
                progress = 0f,
                errorMessageResId = messageResId,
            )
        }
    }

    private fun updateProjectState(
        projectId: String,
        transform: (ProjectMixdownState) -> ProjectMixdownState,
    ) {
        mixdownByProjectId.update { current ->
            val previous = current[projectId] ?: ProjectMixdownState()
            current + (projectId to transform(previous))
        }
    }

    private companion object {
        const val TAG = "ProjectMixdownCoordinator"
    }
}
