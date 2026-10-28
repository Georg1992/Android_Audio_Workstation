package com.georgv.audioworkstation.ui.screens.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.online.share.CardMedia
import com.georgv.audioworkstation.online.share.SharedProject
import com.georgv.audioworkstation.online.share.SharedProjects
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CommunityHomeUiState(
    val sessionKnown: Boolean = false,
    val email: String? = null,
    val name: String? = null,
    val projects: List<SharedProject> = emptyList(),
    val busy: Boolean = false,
    val preparing: Boolean = false,
    val loadingProjectId: String? = null,
)

@HiltViewModel
class CommunityHomeViewModel @Inject constructor(
    private val sessions: AccountSessionStore,
    private val accounts: CognitoAccounts,
    private val sharedProjects: SharedProjects,
    private val localProjects: LocalProjectLookup,
    private val player: CardPlayback,
    private val media: CardMedia,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val preparing = MutableStateFlow(false)
    private val loadingProjectId = MutableStateFlow<String?>(null)
    private val projects = MutableStateFlow<List<SharedProject>>(emptyList())
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    private val opens = Channel<String>(capacity = Channel.BUFFERED)
    private val transportState = MutableStateFlow(CardTransport())
    private val imagePathsState = MutableStateFlow<Map<String, String>>(emptyMap())
    private var watchJob: Job? = null

    val userMessages = messages.receiveAsFlow()
    val openProject = opens.receiveAsFlow()
    val transport: StateFlow<CardTransport> = transportState
    val imagePaths: StateFlow<Map<String, String>> = imagePathsState

    init {
        viewModelScope.launch {
            sessions.state.collect {
                try {
                    load()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    report(R.string.error_shared_projects_failed, error)
                }
            }
        }
    }

    val uiState: StateFlow<CommunityHomeUiState> =
        combine(
            sessions.state,
            busy,
            projects,
            preparing,
            loadingProjectId,
        ) { session, isBusy, shared, isPreparing, loadingId ->
            CommunityHomeUiState(
                sessionKnown = true,
                email = session?.email,
                name = session?.name,
                projects = shared,
                busy = isBusy,
                preparing = isPreparing,
                loadingProjectId = loadingId,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            CommunityHomeUiState(),
        )

    fun reload() {
        viewModelScope.launch {
            try {
                load()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                report(R.string.error_shared_projects_failed, error)
            }
        }
    }

    fun play(projectId: String) {
        if (loadingProjectId.value != null) return
        viewModelScope.launch {
            loadingProjectId.value = projectId
            try {
                val file = media.audio(projectId)
                player.play(projectId, file)
                transportState.value = player.read()
                watch()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                report(R.string.error_card_playback_failed, error)
            } finally {
                loadingProjectId.value = null
            }
        }
    }

    fun stop() {
        player.stop()
        transportState.value = player.read()
    }

    fun seek(positionMs: Int) {
        player.seekTo(positionMs)
        transportState.value = player.read()
    }

    fun open(projectId: String) {
        viewModelScope.launch {
            try {
                if (localProjects.exists(projectId)) {
                    opens.send(projectId)
                } else {
                    messages.send(UiMessage(R.string.error_card_not_on_phone))
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                report(R.string.error_card_not_on_phone, error)
            }
        }
    }

    fun loadImage(projectId: String, hash: String) {
        if (imagePathsState.value.containsKey(hash)) return
        viewModelScope.launch {
            try {
                val file = media.image(projectId, hash)
                imagePathsState.value = imagePathsState.value + (hash to file.absolutePath)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                report(R.string.error_card_image_failed, error)
            }
        }
    }

    fun signOut() {
        submit {
            val session = sessions.current() ?: return@submit
            sessions.clear()
            try {
                accounts.signOut(session.refreshToken)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "token revoke failed", error)
            }
        }
    }

    override fun onCleared() {
        watchJob?.cancel()
        player.stop()
        super.onCleared()
    }

    private suspend fun load() {
        if (sessions.current() == null) {
            projects.value = emptyList()
            return
        }
        preparing.value = true
        try {
            localProjects.publishOwnerNames()
            localProjects.publishMixes()
            projects.value = sharedProjects.list()
        } finally {
            preparing.value = false
        }
    }

    private fun watch() {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (isActive) {
                val read = player.read()
                transportState.value = read
                if (!read.playing) break
                delay(TICK_MS)
            }
        }
    }

    private fun submit(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "account request failed", error)
                messages.send(UiMessage(R.string.error_sign_out_failed))
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun report(message: Int, error: Exception) {
        logWarning(TAG, "community card failed", error)
        messages.send(UiMessage(message))
    }

    private companion object {
        const val TAG = "CommunityHomeViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
        const val TICK_MS = 100L
    }
}
