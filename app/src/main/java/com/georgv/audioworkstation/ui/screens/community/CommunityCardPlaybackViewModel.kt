package com.georgv.audioworkstation.ui.screens.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.ui.reportedFailure
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.online.share.CardMedia
import com.georgv.audioworkstation.ui.playback.CardTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class CommunityCardPlaybackViewModel @Inject constructor(
    private val player: CardPlayback,
    private val media: CardMedia,
) : ViewModel() {
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    private val loadingProjectIdState = MutableStateFlow<String?>(null)
    private val transportState = MutableStateFlow(CardTransport())
    private var watchJob: Job? = null

    val userMessages = messages.receiveAsFlow()
    val transport: StateFlow<CardTransport> = transportState
    val loadingProjectId: StateFlow<String?> = loadingProjectIdState

    fun play(projectId: String) {
        if (loadingProjectIdState.value != null) return
        viewModelScope.launch {
            loadingProjectIdState.value = projectId
            try {
                val url = withContext(Dispatchers.IO) { media.mixUrl(projectId) }
                withContext(Dispatchers.IO) { player.play(projectId, url) }
                transportState.value = player.read()
                watch()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                report(error)
            } finally {
                loadingProjectIdState.value = null
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

    override fun onCleared() {
        watchJob?.cancel()
        player.release()
        super.onCleared()
    }

    private fun watch() {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (isActive) {
                val failure = player.pullFailure()
                if (failure != null) report(failure)
                val read = player.read()
                transportState.value = read
                if (!read.playing) break
                delay(TICK_MS)
            }
        }
    }

    private suspend fun report(error: Exception) {
        logWarning(TAG, "community card failed", error)
        messages.send(UiMessage(reportedFailure(error, R.string.error_card_playback_failed)))
    }

    private companion object {
        const val TAG = "CommunityCardPlaybackViewModel"
        const val TICK_MS = 100L
    }
}
