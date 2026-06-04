package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.ui.components.WaveformState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class TrackImportUiState(
    val progress: Float = 0f,
)

/**
 * In-memory import progress keyed by track id.
 * Persisted [TrackEntity.importStatus] survives rotation; live decode progress lives here.
 */
internal class ProjectImportUiCoordinator {
    private val importUiState = MutableStateFlow<Map<String, TrackImportUiState>>(emptyMap())

    val importUiByTrackId: StateFlow<Map<String, TrackImportUiState>> = importUiState.asStateFlow()

    fun setProgress(trackId: String, progress: Float) {
        importUiState.update { current ->
            val previous = current[trackId]
            if (previous != null && kotlin.math.abs(previous.progress - progress) < 0.01f) {
                return@update current
            }
            current + (trackId to TrackImportUiState(progress = progress))
        }
    }

    fun beginImport(trackId: String) {
        importUiState.update { current ->
            current + (trackId to TrackImportUiState(progress = 0f))
        }
    }

    fun clear(trackId: String) {
        importUiState.update { current -> current - trackId }
    }

    fun resetWhenProjectChanges() {
        importUiState.value = emptyMap()
    }

    fun mergeWaveformStates(
        base: Map<String, WaveformState>,
        tracks: List<com.georgv.audioworkstation.data.db.entities.TrackEntity>,
    ): Map<String, WaveformState> {
        val hasImportTracks =
            tracks.any { track ->
                track.importStatus == TrackImportStatus.IMPORTING ||
                    track.importStatus == TrackImportStatus.FAILED
            }
        if (!hasImportTracks && importUiState.value.isEmpty()) return base
        val merged = base.toMutableMap()
        tracks.forEach { track ->
            when (track.importStatus) {
                TrackImportStatus.IMPORTING -> {
                    val ui = importUiState.value[track.id]
                    merged[track.id] =
                        WaveformState.Importing(
                            progress = ui?.progress ?: 0f,
                        )
                }
                TrackImportStatus.FAILED ->
                    merged[track.id] = WaveformState.ImportFailed
                TrackImportStatus.READY -> Unit
            }
        }
        return merged
    }
}
