package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.engine.NativeEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared playback-active and recording-level polls. Playback and capture both start them,
 * and release clears both, so they stay on one object.
 */
@Singleton
class NativeAudioMonitors @Inject constructor(
    private val nativeEngine: NativeEngine,
) {
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var recordingLevelJob: Job? = null

    private val _playbackState = MutableStateFlow(false)
    val playbackState: StateFlow<Boolean> = _playbackState.asStateFlow()
    private val _recordingInputLevel = MutableStateFlow(0f)
    val recordingInputLevel: StateFlow<Float> = _recordingInputLevel.asStateFlow()

    fun monitorPlaybackCompletion(waitForActiveFirst: Boolean = false) {
        monitorJob?.cancel()
        monitorJob =
            monitorScope.launch {
                if (waitForActiveFirst) {
                    while (!nativeEngine.isPlaybackActive()) {
                        delay(POLL_INTERVAL_MS)
                    }
                }
                _playbackState.value = true
                while (nativeEngine.isPlaybackActive()) {
                    delay(POLL_INTERVAL_MS)
                }
                _playbackState.value = false
            }
    }

    fun cancelPlaybackMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun markPlaybackStopped() {
        _playbackState.value = false
    }

    fun monitorRecordingInputLevel() {
        recordingLevelJob?.cancel()
        recordingLevelJob = monitorScope.launch {
            var smoothed = 0f
            while (true) {
                val target = scaleRecordingLevelForDisplay(nativeEngine.recordingInputLevel())
                val coefficient =
                    if (target > smoothed) RECORDING_LEVEL_ATTACK else RECORDING_LEVEL_RELEASE
                smoothed += (target - smoothed) * coefficient
                _recordingInputLevel.value = smoothed.coerceIn(0f, 1f)
                delay(RECORDING_LEVEL_POLL_INTERVAL_MS)
            }
        }
    }

    fun cancelRecordingLevelMonitor() {
        recordingLevelJob?.cancel()
        recordingLevelJob = null
    }

    fun markRecordingLevelSilent() {
        _recordingInputLevel.value = 0f
    }

    /** Same order as the previous combined controller release: cancel both polls, then zero both levels. */
    fun releaseMonitors() {
        cancelPlaybackMonitor()
        cancelRecordingLevelMonitor()
        markPlaybackStopped()
        markRecordingLevelSilent()
    }

    private fun scaleRecordingLevelForDisplay(level: Float): Float {
        val boosted = (level.coerceIn(0f, 1f) * RECORDING_LEVEL_DISPLAY_GAIN).coerceIn(0f, 1f)
        if (boosted <= 0.0001f) return 0f
        return boosted.pow(RECORDING_LEVEL_DISPLAY_EXPONENT).coerceIn(0f, 1f)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 50L
        const val RECORDING_LEVEL_POLL_INTERVAL_MS = 33L
        const val RECORDING_LEVEL_ATTACK = 0.88f
        const val RECORDING_LEVEL_RELEASE = 0.06f
        const val RECORDING_LEVEL_DISPLAY_GAIN = 2.0f
        const val RECORDING_LEVEL_DISPLAY_EXPONENT = 0.5f
    }
}
