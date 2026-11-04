package com.georgv.audioworkstation.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.ui.reportedFailure
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.share.ProjectShare
import com.georgv.audioworkstation.online.share.SharedProject
import com.georgv.audioworkstation.online.share.SharedProjects
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryShareViewModel @Inject constructor(
    private val sessions: AccountSessionStore,
    private val projectShare: ProjectShare,
    private val sharedProjects: SharedProjects,
    private val projects: ProjectRepository,
) : ViewModel() {
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()

    /** The library list itself does not change with the account. Share is only offered while signed in. */
    val shareEnabled: StateFlow<Boolean> =
        sessions.state
            .map { session -> session != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val sharingIds = MutableStateFlow<Set<String>>(emptySet())
    val sharingProjectIds: StateFlow<Set<String>> = sharingIds
    private val agreementGeneration = AtomicInteger(0)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            showExistingShares()
        }
    }

    fun shareProject(projectId: String) {
        runWhileSharing(projectId, R.string.library_share_done, R.string.error_share_project_failed) {
            projectShare.share(projectId)
        }
    }

    fun unshareProject(projectId: String) {
        runWhileSharing(projectId, R.string.library_unshare_done, R.string.error_unshare_project_failed) {
            projectShare.unshare(projectId)
        }
    }

    private suspend fun showExistingShares() {
        try {
            val session = sessions.current() ?: return
            if (session.name.isEmpty()) return
            val generation = agreementGeneration.get()
            val listed = sharedProjects.list()
            if (generation != agreementGeneration.get()) return
            markListedShares(session, listed, generation)
            clearMissingShares(listed, generation)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            logWarning(TAG, "community share status was not applied", error)
        }
    }

    private suspend fun markListedShares(
        session: AccountSession,
        listed: List<SharedProject>,
        generation: Int,
    ) {
        for (row in listed) {
            if (row.ownerName != session.name || row.id in sharingIds.value) continue
            val project = projects.observeProject(row.id).first() ?: continue
            val alreadyShared = project.syncStatus == SyncStatus.SYNCED &&
                project.ownerUserId == session.accountId
            if (alreadyShared || generation != agreementGeneration.get()) continue
            projects.upsertProject(
                project.copy(syncStatus = SyncStatus.SYNCED, ownerUserId = session.accountId),
            )
        }
    }

    private suspend fun clearMissingShares(listed: List<SharedProject>, generation: Int) {
        if (!projects.projectsReady.value) projects.projectsReady.first { ready -> ready }
        val present = listed.map { row -> row.id }.toSet()
        for (project in projects.observeProjects().first()) {
            if (project.id in present || project.id in sharingIds.value) continue
            val shared = project.syncStatus == SyncStatus.SYNCED || project.syncStatus == SyncStatus.SYNCING
            if (!shared || generation != agreementGeneration.get()) continue
            projects.upsertProject(project.copy(syncStatus = SyncStatus.LOCAL, ownerUserId = null))
        }
    }

    private fun runWhileSharing(
        projectId: String,
        doneMessage: Int,
        failedMessage: Int,
        work: suspend () -> Unit,
    ) {
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
        agreementGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                work()
                messages.send(UiMessage(doneMessage))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "share action failed: $projectId", error)
                messages.send(UiMessage(reportedFailure(error, failedMessage)))
            } finally {
                sharingIds.update { current -> current - projectId }
            }
        }
    }

    private companion object {
        const val TAG = "LibraryShareViewModel"
    }
}
