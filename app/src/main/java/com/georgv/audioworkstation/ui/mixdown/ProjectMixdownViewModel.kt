package com.georgv.audioworkstation.ui.mixdown

import androidx.lifecycle.ViewModel
import com.georgv.audioworkstation.core.audio.ProjectMixdownCoordinator
import com.georgv.audioworkstation.core.audio.ProjectMixdownState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin UI entry point for project mixdown. All work runs in [ProjectMixdownCoordinator]
 * on a process-scoped coroutine scope and survives Project → Library navigation.
 */
@HiltViewModel
class ProjectMixdownViewModel @Inject constructor(
    private val mixdownCoordinator: ProjectMixdownCoordinator,
) : ViewModel() {

    val mixdownByProjectIdState: StateFlow<Map<String, ProjectMixdownState>> =
        mixdownCoordinator.mixdownByProjectIdState

    fun requestMixdown(projectId: String, selectedTrackIds: Set<String>): Boolean =
        mixdownCoordinator.startMixdown(projectId, selectedTrackIds)

    fun mixdownState(projectId: String): ProjectMixdownState =
        mixdownCoordinator.mixdownState(projectId)
}
