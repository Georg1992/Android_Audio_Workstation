package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.core.audio.waveform.WavWaveformPeakExtractor
import com.georgv.audioworkstation.ui.components.WaveformState
import com.georgv.audioworkstation.core.audio.waveform.writeMonoPcm16Wav
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectWaveformPeakCoordinatorTest {

    @get:Rule
    val mainDispatcherRule = ProjectViewModelMainDispatcherRule()

    @Test
    fun `refreshPeakRequests re-extracts when same wav path content changes`() =
        runTest(mainDispatcherRule.dispatcher) {
            val wav = File.createTempFile("coord-waveform", ".wav").apply { deleteOnExit() }
            writeMonoPcm16Wav(
                file = wav,
                samples = shortArrayOf(0, 1_000, 2_000, 3_000, 4_000, 5_000, 6_000, 7_000),
                sampleRateHz = 8_000,
            )
            val states = MutableStateFlow<Map<String, WaveformState>>(emptyMap())
            var tracksSnapshot = emptyList<TrackEntity>()
            val extractor = WavWaveformPeakExtractor(ioDispatcher = mainDispatcherRule.dispatcher)
            val coordinator =
                ProjectWaveformPeakCoordinator(
                    scope = this,
                    waveformPeakExtractor = extractor,
                    waveformStatesByTrackId = states,
                    tracksSnapshot = { tracksSnapshot },
                    ioDispatcher = mainDispatcherRule.dispatcher,
                )
            val track =
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = wav.absolutePath,
                    duration = 1_000L,
                )
            tracksSnapshot = listOf(track)

            coordinator.refreshPeakRequests(tracksSnapshot)
            advanceUntilIdle()
            val quietPeaks = (states.value["a"] as WaveformState.Ready).peaks

            writeMonoPcm16Wav(
                file = wav,
                samples = ShortArray(16) { Short.MAX_VALUE },
                sampleRateHz = 8_000,
            )
            coordinator.refreshPeakRequests(tracksSnapshot)
            advanceUntilIdle()

            val loudPeaks = (states.value["a"] as WaveformState.Ready).peaks
            assertNotEquals(quietPeaks.sourceDurationMs, loudPeaks.sourceDurationMs)
            assertNotEquals(quietPeaks.amplitudes, loudPeaks.amplitudes)
        }

    @Test
    fun `refreshPeakRequests uses updated track duration for timeline clip after punch`() =
        runTest(mainDispatcherRule.dispatcher) {
            val wav = File.createTempFile("coord-duration", ".wav").apply { deleteOnExit() }
            writeMonoPcm16Wav(
                file = wav,
                samples = ShortArray(20_000) { Short.MAX_VALUE },
                sampleRateHz = 1_000,
            )
            val states = MutableStateFlow<Map<String, WaveformState>>(emptyMap())
            var tracksSnapshot = emptyList<TrackEntity>()
            val extractor = WavWaveformPeakExtractor(ioDispatcher = mainDispatcherRule.dispatcher)
            val coordinator =
                ProjectWaveformPeakCoordinator(
                    scope = this,
                    waveformPeakExtractor = extractor,
                    waveformStatesByTrackId = states,
                    tracksSnapshot = { tracksSnapshot },
                    ioDispatcher = mainDispatcherRule.dispatcher,
                )
            val track =
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = wav.absolutePath,
                    duration = 20_000L,
                )
            tracksSnapshot = listOf(track)
            coordinator.refreshPeakRequests(tracksSnapshot)
            advanceUntilIdle()
            val beforeDuration =
                (states.value["a"] as WaveformState.Ready).peaks.sourceDurationMs

            writeMonoPcm16Wav(
                file = wav,
                samples = ShortArray(23_000) { Short.MAX_VALUE },
                sampleRateHz = 1_000,
            )
            tracksSnapshot = listOf(track.copy(duration = 23_000L))
            coordinator.refreshPeakRequests(tracksSnapshot)
            advanceUntilIdle()

            val afterDuration =
                (states.value["a"] as WaveformState.Ready).peaks.sourceDurationMs
            assertEquals(20_000L, beforeDuration)
            assertEquals(23_000L, afterDuration)
            assertTrue(afterDuration > beforeDuration)
        }
}
