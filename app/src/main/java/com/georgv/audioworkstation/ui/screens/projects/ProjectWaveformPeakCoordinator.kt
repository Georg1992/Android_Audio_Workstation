package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.WaveformState
import com.georgv.audioworkstation.ui.components.WavWaveformPeakExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal class ProjectWaveformPeakCoordinator(
    private val scope: CoroutineScope,
    private val waveformPeakExtractor: WavWaveformPeakExtractor,
    private val waveformStatesByTrackId: MutableStateFlow<Map<String, WaveformState>>,
    private val tracksSnapshot: () -> List<TrackEntity>,
) {
    private val waveformPeakPathsByTrackId = mutableMapOf<String, String>()
    private val waveformExtractionsInFlight = mutableSetOf<String>()

    fun resetWhenProjectChanges() {
        waveformStatesByTrackId.value = emptyMap()
        waveformPeakPathsByTrackId.clear()
        waveformExtractionsInFlight.clear()
    }

    fun refreshPeakRequests(tracks: List<TrackEntity>) {
        val playableTracks = tracks.filter { it.wavFilePath.isNotBlank() && !it.isRecording }
        val playableIds = playableTracks.mapTo(mutableSetOf()) { it.id }
        val currentStates = waveformStatesByTrackId.value.toMutableMap()

        currentStates.keys.retainAll(playableIds)
        waveformPeakPathsByTrackId.keys.retainAll(playableIds)
        waveformExtractionsInFlight.retainAll(playableIds)

        playableTracks.forEach { track ->
            val cachedPath = waveformPeakPathsByTrackId[track.id]
            if (cachedPath != track.wavFilePath) {
                currentStates.remove(track.id)
                waveformPeakPathsByTrackId.remove(track.id)
            }
        }
        if (currentStates != waveformStatesByTrackId.value) {
            waveformStatesByTrackId.value = currentStates
        }

        playableTracks.forEach { track ->
            if (waveformPeakPathsByTrackId[track.id] == track.wavFilePath) return@forEach
            if (!waveformExtractionsInFlight.add(track.id)) return@forEach
            waveformPeakPathsByTrackId[track.id] = track.wavFilePath
            waveformStatesByTrackId.value =
                waveformStatesByTrackId.value + (track.id to WaveformState.Loading)
            scope.launch {
                val peaks = waveformPeakExtractor.extract(track.wavFilePath)
                waveformExtractionsInFlight.remove(track.id)
                if (tracksSnapshot().any {
                        it.id == track.id &&
                            it.wavFilePath == track.wavFilePath &&
                            !it.isRecording
                    }
                ) {
                    val state =
                        if (peaks == null) {
                            WaveformState.Failed
                        } else {
                            WaveformState.Ready(peaks)
                        }
                    waveformStatesByTrackId.value = waveformStatesByTrackId.value + (track.id to state)
                }
            }
        }
    }
}
