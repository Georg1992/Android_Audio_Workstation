package com.georgv.audioworkstation.ui.screens.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.online.share.CardMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class CommunityCardImageViewModel @Inject constructor(
    private val media: CardMedia,
) : ViewModel() {
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    private val imagePathsState = MutableStateFlow<Map<String, String>>(emptyMap())

    val userMessages = messages.receiveAsFlow()
    val imagePaths: StateFlow<Map<String, String>> = imagePathsState

    fun loadImage(projectId: String, hash: String) {
        if (imagePathsState.value.containsKey(hash)) return
        viewModelScope.launch {
            try {
                val file = media.image(projectId, hash)
                imagePathsState.value = imagePathsState.value + (hash to file.absolutePath)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "community card failed", error)
                messages.send(UiMessage(R.string.error_card_image_failed))
            }
        }
    }

    private companion object {
        const val TAG = "CommunityCardImageViewModel"
    }
}
