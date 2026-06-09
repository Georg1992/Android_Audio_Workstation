package com.georgv.audioworkstation.core.audio.capability.audit

import com.georgv.audioworkstation.core.audio.capability.DeviceAudioCapabilityProfile
import com.georgv.audioworkstation.core.audio.capability.MeasuredCalibrationData
import com.georgv.audioworkstation.core.audio.latency.AudioLivePathType
import com.georgv.audioworkstation.engine.AudioCallbackCostSnapshot
import com.georgv.audioworkstation.engine.AudioInputLoopCostSnapshot
import com.georgv.audioworkstation.engine.NativeEngine
import com.georgv.audioworkstation.engine.OboeStreamCapabilityProbe
import com.georgv.audioworkstation.engine.PlaybackSessionTimings
import com.georgv.audioworkstation.engine.SoftwareBufferProfile
import kotlin.math.max

data class AppLatencyAuditResult(
    val outputQueueFrames: Int,
    val renderInCallback: Boolean,
    val extraOutputBufferFrames: Int,
    val outputPrerollFrames: Int,
    val outputBufferDelayMs: Double,
    val inputReadFrames: Int,
    val inputReadBlockMs: Double,
    val inputExtraQueueFrames: Int,
    val inputBufferDelayMs: Double,
    val wavWriteAsyncDoesNotBlockCapture: Boolean,
    val compensationDisabled: Boolean,
    val startupDelayExcludedFromCompensation: Boolean,
    val placementUsesStartupDelay: Boolean,
    val captureDelaySource: String,
    val pathType: AudioLivePathType,
    val outputCallbackCost: AudioCallbackCostSnapshot?,
    val inputLoopCost: AudioInputLoopCostSnapshot?,
    val appAddedOutputMsEstimate: Double?,
    val appAddedInputMsEstimate: Double?,
    val appAddedLatencyConfidence: AppAddedLatencyConfidence,
    val callbackCpuLoadPercent: Double?,
)

object AppLatencyAuditBuilder {
    private const val ExtraOutputBufferFrames = 0
    fun build(
        nativeEngine: NativeEngine,
        pathType: AudioLivePathType,
        profile: DeviceAudioCapabilityProfile?,
    ): AppLatencyAuditResult {
        val outputProbe = nativeEngine.probeOutputStreamCapability()
        val inputProbe = nativeEngine.probeInputStreamCapability()
        val bufferProfile = nativeEngine.softwareBufferProfile()
        val timings = nativeEngine.playbackSessionTimings()
        val outputCallbackCost = nativeEngine.outputCallbackCostSnapshot()
        val inputLoopCost = nativeEngine.inputLoopCostSnapshot()
        val sampleRateHz =
            profile?.sampleRate?.takeIf { it > 0 }
                ?: outputProbe?.sampleRateHz?.takeIf { it > 0 }
                ?: 44_100

        val extraOutputBufferFrames = ExtraOutputBufferFrames
        val outputPrerollFrames = outputPrerollFrames(bufferProfile, timings, sampleRateHz)
        val inputReadFrames = inputReadFrames(bufferProfile, inputProbe)
        val inputReadBlockMs = framesToMs(inputReadFrames, sampleRateHz)
        val inputExtraQueueFrames = 0
        val inputBufferDelayMs =
            extraInputReadDelayMs(inputReadFrames, inputProbe, sampleRateHz)
        val estimate =
            AppLatencyEstimateCalculator.estimate(
                outputCallbackCost = outputCallbackCost,
                inputLoopCost = inputLoopCost,
                extraOutputBufferFrames = extraOutputBufferFrames,
                outputQueueFrames = 0,
                inputExtraQueueFrames = inputExtraQueueFrames,
                sampleRateHz = sampleRateHz,
            )

        return AppLatencyAuditResult(
            outputQueueFrames = 0,
            renderInCallback = true,
            extraOutputBufferFrames = extraOutputBufferFrames,
            outputPrerollFrames = outputPrerollFrames,
            outputBufferDelayMs = framesToMs(extraOutputBufferFrames, sampleRateHz),
            inputReadFrames = inputReadFrames,
            inputReadBlockMs = inputReadBlockMs,
            inputExtraQueueFrames = inputExtraQueueFrames,
            inputBufferDelayMs = inputBufferDelayMs,
            wavWriteAsyncDoesNotBlockCapture = liveCaptureWavWriteDoesNotBlockCapture(pathType),
            compensationDisabled = true,
            startupDelayExcludedFromCompensation = true,
            placementUsesStartupDelay = true,
            captureDelaySource = captureDelaySource(profile?.calibration, inputProbe),
            pathType = pathType,
            outputCallbackCost = outputCallbackCost,
            inputLoopCost = inputLoopCost,
            appAddedOutputMsEstimate = estimate.appAddedOutputMsEstimate,
            appAddedInputMsEstimate = estimate.appAddedInputMsEstimate,
            appAddedLatencyConfidence = estimate.confidence,
            callbackCpuLoadPercent =
                AppLatencyEstimateCalculator.callbackCpuLoadPercent(
                    outputCallbackCost,
                    sampleRateHz,
                ),
        )
    }

    private fun outputPrerollFrames(
        bufferProfile: SoftwareBufferProfile?,
        timings: PlaybackSessionTimings?,
        sampleRateHz: Int,
    ): Int {
        timings?.prerollFrames?.takeIf { it > 0 }?.let { return it }
        if (bufferProfile == null || sampleRateHz <= 0) {
            return 0
        }
        return sampleRateHz * bufferProfile.prerollWallMs / 1000
    }

    private fun inputReadFrames(
        bufferProfile: SoftwareBufferProfile?,
        inputProbe: OboeStreamCapabilityProbe?,
    ): Int =
        bufferProfile?.inputReadFrames?.takeIf { it > 0 }
            ?: inputProbe?.blockFrames?.takeIf { it > 0 }
            ?: inputProbe?.framesPerBurst?.takeIf { it > 0 }
            ?: 0

    private fun extraInputReadDelayMs(
        inputReadFrames: Int,
        inputProbe: OboeStreamCapabilityProbe?,
        sampleRateHz: Int,
    ): Double {
        val burst = inputProbe?.framesPerBurst?.takeIf { it > 0 } ?: return 0.0
        val extraFrames = max(0, inputReadFrames - burst)
        return framesToMs(extraFrames, sampleRateHz)
    }

    private fun captureDelaySource(
        calibration: MeasuredCalibrationData?,
        inputProbe: OboeStreamCapabilityProbe?,
    ): String =
        when {
            calibration?.hasCaptureDelayEstimate() == true -> "calibration"
            inputProbe?.timestampAvailable == true &&
                inputProbe.estimatedStreamLatencyMs != null -> "timestamp"
            else -> "unknown"
        }

    private fun liveCaptureWavWriteDoesNotBlockCapture(pathType: AudioLivePathType): Boolean =
        when (pathType) {
            AudioLivePathType.RECORDING_ONLY,
            AudioLivePathType.OVERDUB,
            -> true
            else -> true
        }

    private fun framesToMs(frames: Int, sampleRateHz: Int): Double {
        if (frames <= 0 || sampleRateHz <= 0) {
            return 0.0
        }
        return frames * 1000.0 / sampleRateHz.toDouble()
    }
}
