package com.georgv.audioworkstation.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.ui.playback.CardTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@HiltViewModel
class LibraryPreviewViewModel @Inject constructor(
    private val mixPreviewPlayer: LibraryMixPreviewPlayer,
) : ViewModel() {
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()
    val transport: StateFlow<CardTransport> = mixPreviewPlayer.transportState
    private var watchJob: Job? = null

    fun play(item: LibraryProjectItem) {
        val path = item.mixdown.mixdownWavPath
        val mixReady = path != null && item.mixdown.hasMixPreview && File(path).isFile
        if (item.mixdown.isMixing || !mixReady) {
            viewModelScope.launch { messages.send(UiMessage(R.string.library_no_mix_yet)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val started = mixPreviewPlayer.play(item.project.id, path)
            if (!started) {
                messages.send(UiMessage(R.string.error_mix_preview_failed))
                return@launch
            }
            watchPreview()
        }
    }

    fun stop() {
        viewModelScope.launch { mixPreviewPlayer.stop() }
    }

    fun seek(positionMs: Int) {
        viewModelScope.launch { mixPreviewPlayer.seekTo(positionMs) }
    }

    fun hold() {
        viewModelScope.launch { mixPreviewPlayer.holdForScrub() }
    }

    override fun onCleared() {
        watchJob?.cancel()
        runBlocking { mixPreviewPlayer.stopAll() }
        super.onCleared()
    }

    private fun watchPreview() {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (isActive) {
                mixPreviewPlayer.tick()
                if (mixPreviewPlayer.playingProjectIdState.value == null) break
                delay(PREVIEW_TICK_MS)
            }
        }
    }

    private companion object {
        const val PREVIEW_TICK_MS = 100L
    }
}
