package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.waveform.WavWaveformPeakExtractor
import com.georgv.audioworkstation.core.audio.waveform.WaveformPeaks
import kotlinx.coroutines.CoroutineDispatcher

/** Skips WAV I/O; [ProjectViewModel] maps a null result to [com.georgv.audioworkstation.ui.components.WaveformState.Failed]. */
internal class NoOpWaveformPeakExtractor(
    ioDispatcher: CoroutineDispatcher,
) : WavWaveformPeakExtractor(ioDispatcher = ioDispatcher) {
    override suspend fun extract(wavPath: String): WaveformPeaks? = null
}
