package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.MasterPeakIndicatorLevel
import com.georgv.audioworkstation.core.audio.MasterPeakMeter
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.ui.diagnostics.ThreadingDiagnostics
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Session master peak hold polling, display reset, and overload warning gating.
 * Transport phase ownership stays in [PlayheadTransportController]; this only observes it.
 */
class MasterPeakController(
    private val scope: CoroutineScope,
    private val audioController: AudioController,
    private val dispatchers: AppDispatchers,
    transportPhase: StateFlow<TransportPlaybackPhase>,
    private val onOverloadWarning: () -> Unit,
) {
    val peakHoldLinear = MutableStateFlow(0f)

    private var pollJob: Job? = null
    private var pollEnabledForTests = true
    private var overloadWarningShownThisSession = false

    init {
        scope.launch {
            transportPhase.collect { phase ->
                pollJob?.cancel()
                pollJob = null
                if (phase == TransportPlaybackPhase.Playing && pollEnabledForTests) {
                    pollJob =
                        scope.launch(dispatchers.default) {
                            ThreadingDiagnostics.logPollLoop("default masterPeak")
                            while (isActive) {
                                val nativePeak = audioController.readMasterPeakHoldLinear()
                                withContext(dispatchers.main) {
                                    val updated = max(peakHoldLinear.value, nativePeak)
                                    if (updated != peakHoldLinear.value) {
                                        peakHoldLinear.value = updated
                                        maybeEmitOverloadWarning(updated)
                                    }
                                }
                                delay(MASTER_PEAK_HOLD_POLL_MS)
                            }
                        }
                }
            }
        }
    }

    fun resetDisplayAndNativeHold() {
        peakHoldLinear.value = 0f
        overloadWarningShownThisSession = false
        scope.launch(dispatchers.audioIo) {
            audioController.resetMasterPeakHold()
        }
    }

    fun onIndicatorClicked() {
        peakHoldLinear.value = 0f
        scope.launch(dispatchers.audioIo) {
            audioController.resetMasterPeakHold()
        }
    }

    fun clearDisplayOnTeardown() {
        peakHoldLinear.value = 0f
        overloadWarningShownThisSession = false
    }

    fun setPollEnabledForTests(enabled: Boolean) {
        pollEnabledForTests = enabled
        if (!enabled) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    private fun maybeEmitOverloadWarning(peakLinear: Float) {
        if (overloadWarningShownThisSession) return
        if (MasterPeakMeter.indicatorLevelForPeak(peakLinear, isStopped = false) !=
            MasterPeakIndicatorLevel.Red
        ) {
            return
        }
        overloadWarningShownThisSession = true
        onOverloadWarning()
    }

    companion object {
        const val MASTER_PEAK_HOLD_POLL_MS = 150L
    }
}
