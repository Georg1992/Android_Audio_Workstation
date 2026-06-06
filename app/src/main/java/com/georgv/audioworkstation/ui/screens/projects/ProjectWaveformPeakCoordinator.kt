package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.Mp3ImportTiming
import com.georgv.audioworkstation.core.track.hasPersistedPlayableAudio
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.WaveformState
import com.georgv.audioworkstation.ui.components.WavWaveformPeakExtractor
import com.georgv.audioworkstation.ui.components.wavFileContentFingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.georgv.audioworkstation.ui.diagnostics.WaveformRecompositionDiagnostics

/**
 * Post-READY exact waveform extraction for playable tracks.
 * File fingerprinting and extraction run off the main thread; state updates use [scope].
 */
internal class ProjectWaveformPeakCoordinator(
    private val scope: CoroutineScope,
    private val waveformPeakExtractor: WavWaveformPeakExtractor,
    private val waveformStatesByTrackId: MutableStateFlow<Map<String, WaveformState>>,
    private val tracksSnapshot: () -> List<TrackEntity>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val waveformPeakCacheKeysByTrackId = mutableMapOf<String, String>()
    private val waveformExtractionsInFlight = mutableSetOf<String>()
    private val waveformExtractionJobsByTrackId = mutableMapOf<String, Job>()

    fun resetWhenProjectChanges() {
        waveformExtractionJobsByTrackId.values.forEach { it.cancel() }
        waveformExtractionJobsByTrackId.clear()
        WaveformRecompositionDiagnostics.assignWaveformStates(
            waveformStatesByTrackId,
            source = "resetWhenProjectChanges",
            next = emptyMap(),
        )
        waveformPeakCacheKeysByTrackId.clear()
        waveformExtractionsInFlight.clear()
    }

    fun refreshPeakRequests(tracks: List<TrackEntity>) {
        scope.launch {
            applyRefreshPeakRequests(tracks)
        }
    }

    private suspend fun applyRefreshPeakRequests(tracks: List<TrackEntity>) {
        val playableTracks = tracks.filter { it.hasPersistedPlayableAudio() }
        val playableIds = playableTracks.mapTo(mutableSetOf()) { it.id }
        val currentStates = waveformStatesByTrackId.value.toMutableMap()

        currentStates.keys.retainAll(playableIds)
        waveformPeakCacheKeysByTrackId.keys.retainAll(playableIds)
        waveformExtractionJobsByTrackId.keys.toList().forEach { trackId ->
            if (trackId !in playableIds) {
                waveformExtractionJobsByTrackId.remove(trackId)?.cancel()
            }
        }
        waveformExtractionsInFlight.retainAll(playableIds)
        waveformExtractionJobsByTrackId.keys.retainAll(playableIds)

        playableTracks.forEach { track ->
            val cacheKey = trackWaveformCacheKey(track)
            val cachedKey = waveformPeakCacheKeysByTrackId[track.id]
            if (cachedKey != cacheKey && cachedKey != null) {
                currentStates.remove(track.id)
                waveformPeakCacheKeysByTrackId.remove(track.id)
            }
        }
        if (currentStates != waveformStatesByTrackId.value) {
            WaveformRecompositionDiagnostics.assignWaveformStates(
                waveformStatesByTrackId,
                source = "applyRefreshPeakRequests/prune",
                next = currentStates,
            )
        }

        playableTracks.forEach { track ->
            val cacheKey = trackWaveformCacheKey(track) ?: return@forEach
            if (waveformPeakCacheKeysByTrackId[track.id] == cacheKey) return@forEach
            if (!waveformExtractionsInFlight.add(track.id)) return@forEach

            val cachedPeaks =
                withContext(ioDispatcher) {
                    waveformPeakExtractor.peekCachedPeaks(track.wavFilePath)
                }
            if (cachedPeaks != null) {
                waveformPeakCacheKeysByTrackId[track.id] = cacheKey
                waveformExtractionsInFlight.remove(track.id)
                WaveformRecompositionDiagnostics.assignWaveformStates(
                    waveformStatesByTrackId,
                    source = "applyRefreshPeakRequests/cachedPeaks",
                    next = waveformStatesByTrackId.value + (track.id to WaveformState.Ready(cachedPeaks)),
                )
                return@forEach
            }

            waveformPeakCacheKeysByTrackId[track.id] = cacheKey
            WaveformRecompositionDiagnostics.assignWaveformStates(
                waveformStatesByTrackId,
                source = "applyRefreshPeakRequests/startLoading",
                next = waveformStatesByTrackId.value + (track.id to WaveformState.Loading),
            )
            launchExtraction(track = track, cacheKey = cacheKey)
        }
    }

    private fun launchExtraction(track: TrackEntity, cacheKey: String) {
        waveformExtractionJobsByTrackId[track.id]?.cancel()
        waveformExtractionJobsByTrackId[track.id] =
            scope.launch {
                val (peaks, extractDurationMs) =
                    withContext(ioDispatcher) {
                        Mp3ImportTiming.measureWallClock {
                            waveformPeakExtractor.extract(track.wavFilePath)
                        }
                    }
                waveformExtractionsInFlight.remove(track.id)
                waveformExtractionJobsByTrackId.remove(track.id)
                if (!tracksSnapshot().any { trackStillValidForWaveformCache(it, track, cacheKey) }) {
                    return@launch
                }
                Mp3ImportTiming.logPostReadyWaveformExtract(
                    trackId = track.id,
                    durationMs = extractDurationMs,
                    success = peaks != null,
                )
                val state =
                    if (peaks == null) {
                        WaveformState.Failed
                    } else {
                        WaveformState.Ready(peaks)
                    }
                WaveformRecompositionDiagnostics.assignWaveformStates(
                    waveformStatesByTrackId,
                    source = "launchExtraction/complete",
                    next = waveformStatesByTrackId.value + (track.id to state),
                )
                if (state is WaveformState.Ready) {
                    WaveformRecompositionDiagnostics.logTrackBecameReady(track.id)
                }
            }
    }

    private fun trackStillValidForWaveformCache(
        candidate: TrackEntity,
        sourceTrack: TrackEntity,
        cacheKey: String,
    ): Boolean =
        candidate.id == sourceTrack.id &&
            candidate.wavFilePath == sourceTrack.wavFilePath &&
            candidate.hasPersistedPlayableAudio() &&
            trackWaveformCacheKeyBlocking(candidate) == cacheKey

    private suspend fun trackWaveformCacheKey(track: TrackEntity): String? =
        withContext(ioDispatcher) {
            trackWaveformCacheKeyBlocking(track)
        }

    private fun trackWaveformCacheKeyBlocking(track: TrackEntity): String? {
        val fingerprint = wavFileContentFingerprint(track.wavFilePath) ?: return null
        return "$fingerprint|${track.duration ?: 0L}"
    }
}
