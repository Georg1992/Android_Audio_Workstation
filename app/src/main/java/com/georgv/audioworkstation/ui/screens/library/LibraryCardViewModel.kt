package com.georgv.audioworkstation.ui.screens.library

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.ui.reportedFailure
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.share.CardImageStore
import com.georgv.audioworkstation.online.share.checkedCardDescription
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
    val description: String = "",
    val imageChosen: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
)

@HiltViewModel
class LibraryCardViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val projects: ProjectRepository,
    private val paths: AudioFilePathProvider,
    private val images: CardImageStore,
) : ViewModel() {
    private val projectId: String = savedState.get<String>("projectId") ?: error("card project id is missing")
    private val title = MutableStateFlow("")
    private val tags = MutableStateFlow("")
    private val description = MutableStateFlow("")
    private val imageChosen = MutableStateFlow(false)
    private val loaded = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    private val saved = Channel<Unit>(capacity = Channel.BUFFERED)
    private var pendingImage: File? = null

    val userMessages = messages.receiveAsFlow()
    val savedEvent = saved.receiveAsFlow()

    val uiState: StateFlow<LibraryCardUiState> = run {
        val text = combine(title, tags, description) { name, tagText, about ->
            Triple(name, tagText, about)
        }
        val flags = combine(imageChosen, loaded, busy) { chosen, isLoaded, isBusy ->
            Triple(chosen, isLoaded, isBusy)
        }
        combine(text, flags) { words, marks ->
            val (name, tagText, about) = words
            val (chosen, isLoaded, isBusy) = marks
            LibraryCardUiState(
                title = name,
                tags = tagText,
                description = about,
                imageChosen = chosen,
                loaded = isLoaded,
                busy = isBusy,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryCardUiState())
    }

    init {
        viewModelScope.launch {
            try {
                val project = projects.observeProject(projectId).first()
                    ?: error("Project $projectId is not on this phone")
                title.value = project.name.orEmpty()
                tags.value = project.cardTags
                description.value = project.cardDescription
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

    fun setDescription(value: String) {
        description.value = value
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
                val storedDescription = try {
                    checkedCardDescription(description.value)
                } catch (invalid: IllegalStateException) {
                    logWarning(TAG, "description is too long", invalid)
                    busy.value = false
                    messages.trySend(UiMessage(R.string.error_card_description_too_long))
                    return@launch
                }
                val storedTags = checkedCardTags(tags.value).joinToString(", ")
                val picked = pendingImage
                val imagePath = if (picked == null) project.cardImagePath else storedImage(picked)
                val name = title.value.trim().ifBlank { null }
                projects.upsertProject(
                    project.copy(
                        name = name,
                        cardTags = storedTags,
                        cardImagePath = imagePath,
                        cardDescription = storedDescription,
                    ),
                )
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
        messages.send(UiMessage(reportedFailure(error, R.string.error_card_save_failed)))
    }

    private companion object {
        const val TAG = "LibraryCardViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
