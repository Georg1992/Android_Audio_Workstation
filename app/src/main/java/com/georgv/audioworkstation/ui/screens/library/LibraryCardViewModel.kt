package com.georgv.audioworkstation.ui.screens.library

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.share.CardImageStore
import com.georgv.audioworkstation.online.share.ProjectShare
import com.georgv.audioworkstation.online.share.checkedCardTags
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryCardUiState(
    val title: String = "",
    val tags: String = "",
    val imageChosen: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
)

@HiltViewModel
class LibraryCardViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val projects: ProjectRepository,
    private val share: ProjectShare,
    private val paths: AudioFilePathProvider,
    private val images: CardImageStore,
) : ViewModel() {
    private val projectId: String = savedState.get<String>("projectId") ?: error("card project id is missing")
    private val title = MutableStateFlow("")
    private val tags = MutableStateFlow("")
    private val imageChosen = MutableStateFlow(false)
    private val loaded = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    private val saved = Channel<Unit>(capacity = Channel.BUFFERED)
    private var pendingImage: File? = null

    val userMessages = messages.receiveAsFlow()
    val savedEvent = saved.receiveAsFlow()

    val uiState: StateFlow<LibraryCardUiState> =
        combine(title, tags, imageChosen, loaded, busy) { name, tagText, chosen, isLoaded, isBusy ->
            LibraryCardUiState(
                title = name,
                tags = tagText,
                imageChosen = chosen,
                loaded = isLoaded,
                busy = isBusy,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryCardUiState())

    init {
        viewModelScope.launch {
            try {
                val project = projects.observeProject(projectId).first()
                    ?: error("Project $projectId is not on this phone")
                title.value = project.name.orEmpty()
                tags.value = project.cardTags
                loaded.value = true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                report(error)
            }
        }
    }

    fun setTitle(value: String) {
        title.value = value
    }

    fun setTags(value: String) {
        tags.value = value
    }

    fun chooseImage(uri: Uri) {
        try {
            pendingImage = images.copy(uri)
            imageChosen.value = true
        } catch (error: Exception) {
            logWarning(TAG, "card image failed", error)
            messages.trySend(UiMessage(R.string.error_card_image_failed))
        }
    }

    fun save() {
        if (busy.value || !loaded.value) return
        busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val project = projects.observeProject(projectId).first()
                    ?: error("Project $projectId is not on this phone")
                val storedTags = checkedCardTags(tags.value).joinToString(", ")
                val picked = pendingImage
                val imagePath = if (picked == null) project.cardImagePath else storedImage(picked)
                val name = title.value.trim().ifBlank { null }
                projects.upsertProject(
                    project.copy(name = name, cardTags = storedTags, cardImagePath = imagePath),
                )
                if (project.syncStatus == SyncStatus.SYNCED) {
                    share.updateCard(projectId, name.orEmpty(), storedTags, imagePath)
                }
                saved.send(Unit)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                report(error)
            } finally {
                busy.value = false
            }
        }
    }

    private fun storedImage(picked: File): String {
        val directory = paths.projectRecordingDirectory(projectId) ?: error("could not store the card image")
        val dest = File(directory, "card-image")
        picked.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    private suspend fun report(error: Exception) {
        logWarning(TAG, "card save failed", error)
        messages.send(UiMessage(R.string.error_card_save_failed))
    }

    private companion object {
        const val TAG = "LibraryCardViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
