package com.georgv.audioworkstation.ui.screens.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.waveform.WavWaveformPeakExtractor
import com.georgv.audioworkstation.core.track.clampTrimRegionMs
import com.georgv.audioworkstation.core.track.effectiveTrimEndMs
import com.georgv.audioworkstation.core.track.effectiveTrimStartMs
import com.georgv.audioworkstation.core.track.hasPersistedPlayableAudio
import com.georgv.audioworkstation.core.track.sourceDurationMs
import com.georgv.audioworkstation.core.track.trackEditTimelineLayoutDurationMs
import com.georgv.audioworkstation.core.track.trimmedClipDurationMs
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.components.WaveformState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TrackEditUiState(
    val trackName: String = "",
    val sourceDurationMs: Long = 0L,
    val clipStartOffsetMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val timelineLayoutDurationMs: Long = 0L,
    val trimmedDurationMs: Long = 0L,
    val waveformState: WaveformState = WaveformState.Loading,
    val trackMissing: Boolean = false,
)

@HiltViewModel
class TrackEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ProjectRepository,
    private val waveformPeakExtractor: WavWaveformPeakExtractor,
) : ViewModel() {

    private val projectId: String = savedStateHandle.get<String>("projectId").orEmpty()
    private val trackId: String = savedStateHandle.get<String>("trackId").orEmpty()

    private val _uiState = MutableStateFlow(TrackEditUiState())
    val uiState: StateFlow<TrackEditUiState> = _uiState.asStateFlow()

    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()

    private var latestTrack: TrackEntity? = null

    init {
        viewModelScope.launch {
            repo.observeTracks(projectId)
                .map { tracks -> tracks.find { it.id == trackId } }
                .distinctUntilChanged()
                .collect { track ->
                    latestTrack = track
                    if (track == null) {
                        _uiState.value = _uiState.value.copy(trackMissing = true)
                        return@collect
                    }
                    applyTrack(track)
                }
        }
    }

    fun commitTrimRegion(trimStartMs: Long, trimEndMs: Long) {
        val track = currentTrack() ?: return
        val sourceDuration = track.sourceDurationMs()
        if (sourceDuration <= 0L) return
        val (startMs, endMs) = clampTrimRegionMs(trimStartMs, trimEndMs, sourceDuration)
        persistClipEdit(
            track = track,
            clipStartOffsetMs = track.timelineStartOffsetMs,
            trimStartMs = startMs,
            trimEndMs = endMs,
        )
    }

    fun commitClipPosition(clipStartOffsetMs: Long) {
        val track = currentTrack() ?: return
        val trimmedDuration = track.trimmedClipDurationMs()
        val layoutDuration =
            trackEditTimelineLayoutDurationMs(
                clipStartOffsetMs = clipStartOffsetMs,
                trimmedDurationMs = trimmedDuration,
                sourceDurationMs = track.sourceDurationMs(),
            )
        val maxStart = (layoutDuration - trimmedDuration).coerceAtLeast(0L)
        val clampedStart = clipStartOffsetMs.coerceIn(0L, maxStart)
        persistClipEdit(
            track = track,
            clipStartOffsetMs = clampedStart,
            trimStartMs = track.effectiveTrimStartMs(),
            trimEndMs = track.effectiveTrimEndMs(),
        )
    }

    private fun applyTrack(track: TrackEntity) {
        val sourceDuration = track.sourceDurationMs()
        val trimmedDuration = track.trimmedClipDurationMs()
        val layoutDuration =
            trackEditTimelineLayoutDurationMs(
                clipStartOffsetMs = track.timelineStartOffsetMs,
                trimmedDurationMs = trimmedDuration,
                sourceDurationMs = sourceDuration,
            )
        _uiState.value =
            TrackEditUiState(
                trackName = track.name ?: "Track",
                sourceDurationMs = sourceDuration,
                clipStartOffsetMs = track.timelineStartOffsetMs,
                trimStartMs = track.effectiveTrimStartMs(),
                trimEndMs = track.effectiveTrimEndMs(),
                timelineLayoutDurationMs = layoutDuration,
                trimmedDurationMs = trimmedDuration,
                waveformState = _uiState.value.waveformState,
                trackMissing = false,
            )
        if (track.hasPersistedPlayableAudio()) {
            ensureWaveformLoaded(track)
        } else {
            _uiState.value = _uiState.value.copy(waveformState = WaveformState.NoWaveform)
        }
    }

    private fun ensureWaveformLoaded(track: TrackEntity) {
        if (_uiState.value.waveformState is WaveformState.Ready) return
        viewModelScope.launch {
            val cached =
                withContext(Dispatchers.Default) {
                    waveformPeakExtractor.peekCachedPeaks(track.wavFilePath)
                }
            if (cached != null) {
                _uiState.value = _uiState.value.copy(waveformState = WaveformState.Ready(cached))
                return@launch
            }
            _uiState.value = _uiState.value.copy(waveformState = WaveformState.Loading)
            val peaks =
                withContext(Dispatchers.Default) {
                    waveformPeakExtractor.extract(track.wavFilePath)
                }
            _uiState.value =
                _uiState.value.copy(
                    waveformState =
                        if (peaks != null) {
                            WaveformState.Ready(peaks)
                        } else {
                            WaveformState.Failed
                        },
                )
        }
    }

    private fun persistClipEdit(
        track: TrackEntity,
        clipStartOffsetMs: Long,
        trimStartMs: Long,
        trimEndMs: Long,
    ) {
        val sourceDuration = track.sourceDurationMs()
        val (clampedTrimStart, clampedTrimEnd) =
            clampTrimRegionMs(trimStartMs, trimEndMs, sourceDuration)
        val updatedTrack =
            track.copy(
                timelineStartOffsetMs = clipStartOffsetMs.coerceAtLeast(0L),
                trimStartMs = clampedTrimStart,
                trimEndMs = clampedTrimEnd,
            )
        viewModelScope.launch {
            try {
                repo.upsertTrack(updatedTrack)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "failed to save track clip edit", error)
                messages.trySend(UiMessage(R.string.error_track_edit_save_failed))
            }
        }
    }

    private fun currentTrack(): TrackEntity? = latestTrack

    companion object {
        private const val TAG = "TrackEditViewModel"
    }
}
