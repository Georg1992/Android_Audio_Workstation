package com.georgv.audioworkstation.ui.screens.library

import androidx.lifecycle.ViewModel
import com.georgv.audioworkstation.core.util.logWarning
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.ProjectMixdownCoordinator
import com.georgv.audioworkstation.core.audio.ProjectMixdownState
import com.georgv.audioworkstation.core.ui.DataAvailability
import com.georgv.audioworkstation.core.ui.ScreenState
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.share.ProjectShare
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: ProjectRepository,
    private val mixdownCoordinator: ProjectMixdownCoordinator,
    private val mixPreviewPlayer: LibraryMixPreviewPlayer,
    private val sessions: AccountSessionStore,
    private val projectShare: ProjectShare,
) : ViewModel() {

    private val projectsFirstEmissionLogged = AtomicBoolean(false)

    private val reportedMixdownErrors = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            repo.projectsState.collect { projects ->
                mixdownCoordinator.refreshKnownMixdownPaths(projects.map { it.id })
            }
        }
        viewModelScope.launch {
            mixdownCoordinator.mixdownByProjectIdState.collect { states ->
                states.forEach { (projectId, state) ->
                    val errorResId = state.errorMessageResId ?: return@forEach
                    if (reportedMixdownErrors.add(projectId)) {
                        messages.send(UiMessage(errorResId))
                    }
                }
                reportedMixdownErrors.retainAll(
                    states.filterValues { it.errorMessageResId != null }.keys,
                )
            }
        }
    }

    val uiState: StateFlow<ScreenState<LibraryContent>> =
        combine(
            repo.projectsState,
            mixdownCoordinator.mixdownByProjectIdState,
            mixPreviewPlayer.playingProjectIdState,
        ) { projects, mixdownByProjectId, playingProjectId ->
            ScreenState(
                availability = DataAvailability.Ready,
                content =
                    LibraryContent(
                        projects =
                            projects.map { project ->
                                LibraryProjectItem(
                                    project = project,
                                    mixdown =
                                        mixdownByProjectId[project.id]
                                            ?: ProjectMixdownState(),
                                    isPreviewPlaying = playingProjectId == project.id,
                                )
                            },
                    ),
            )
        }
            .onEach { state ->
                if (projectsFirstEmissionLogged.compareAndSet(false, true)) {
                    LibraryDiagnostics.logProjectsFirstEmission(state.content.projects.size)
                }
                LibraryDiagnostics.logStateEmitted(state)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                initialLibraryScreenState(repo, mixdownCoordinator),
            )

    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()

    /** The library list itself does not change with the account. Share is only offered while signed in. */
    val shareEnabled: StateFlow<Boolean> =
        sessions.state
            .map { session -> session != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val sharingIds = MutableStateFlow<Set<String>>(emptySet())
    val sharingProjectIds: StateFlow<Set<String>> = sharingIds

    fun shareProject(projectId: String) {
        var started = false
        sharingIds.update { current ->
            if (projectId in current) {
                current
            } else {
                started = true
                current + projectId
            }
        }
        if (!started) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                projectShare.share(projectId)
                messages.send(UiMessage(R.string.library_share_done))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "shareProject failed: $projectId", error)
                messages.send(UiMessage(R.string.error_share_project_failed))
            } finally {
                sharingIds.update { current -> current - projectId }
            }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            try {
                mixPreviewPlayer.stopIfPlaying(projectId)
                reportedMixdownErrors.remove(projectId)
                mixdownCoordinator.clearProject(projectId)
                repo.deleteProject(projectId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "deleteProject failed: $projectId", error)
                messages.send(UiMessage(R.string.error_delete_project_failed))
            }
        }
    }

    fun onProjectCardBodyClick(item: LibraryProjectItem) {
        val mixFileExists = item.mixdown.mixdownWavPath?.let { File(it).isFile } == true
        val outcome =
            resolveLibraryCardBodyClick(
                mixdown = item.mixdown,
                isCurrentlyPlaying = item.isPreviewPlaying,
                mixFileExists = mixFileExists,
            )
        when (outcome) {
            LibraryCardBodyClickOutcome.IgnoredWhileMixing -> Unit
            LibraryCardBodyClickOutcome.NoMixAvailable ->
                viewModelScope.launch {
                    messages.send(UiMessage(R.string.library_no_mix_yet))
                }

            LibraryCardBodyClickOutcome.TogglePreview -> {
                val wavPath = item.mixdown.mixdownWavPath ?: return
                val started = mixPreviewPlayer.togglePreview(item.project.id, wavPath)
                if (!started && !item.isPreviewPlaying) {
                    viewModelScope.launch {
                        messages.send(UiMessage(R.string.error_mix_preview_failed))
                    }
                }
            }
        }
    }

    /** Prefetch project metadata before Library → Project navigation. */
    fun warmUpProject(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.observeProject(projectId).first()
            repo.observeTracks(projectId).first()
        }
    }

    override fun onCleared() {
        mixPreviewPlayer.stopAll()
        super.onCleared()
    }

    private companion object {
        const val TAG = "LibraryViewModel"

        fun initialLibraryScreenState(
            repo: ProjectRepository,
            mixdownCoordinator: ProjectMixdownCoordinator,
        ): ScreenState<LibraryContent> =
            ScreenState(
                availability =
                    if (repo.projectsReady.value) {
                        DataAvailability.Ready
                    } else {
                        DataAvailability.Pending
                    },
                content =
                    LibraryContent(
                        projects =
                            repo.projectsState.value.map { project ->
                                LibraryProjectItem(
                                    project = project,
                                    mixdown = mixdownCoordinator.mixdownState(project.id),
                                    isPreviewPlaying = false,
                                )
                            },
                    ),
            )
    }
}
