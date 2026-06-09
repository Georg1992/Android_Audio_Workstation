package com.georgv.audioworkstation.core.audio.capability.audit

import com.georgv.audioworkstation.engine.AudioCallbackCostSnapshot
import com.georgv.audioworkstation.engine.AudioInputLoopCostSnapshot

enum class AppAddedLatencyConfidence {
    MEASURED,
    PARTIAL,
    UNAVAILABLE,
}

data class AppAddedLatencyEstimate(
    val appAddedOutputMsEstimate: Double?,
    val appAddedInputMsEstimate: Double?,
    val confidence: AppAddedLatencyConfidence,
)

object AppLatencyEstimateCalculator {
    private const val OUTPUT_SAMPLE_THRESHOLD = 32L
    private const val INPUT_SAMPLE_THRESHOLD = 16L
    private const val SAFE_CALLBACK_CPU_LOAD_PERCENT = 80.0

    fun estimate(
        outputCallbackCost: AudioCallbackCostSnapshot?,
        inputLoopCost: AudioInputLoopCostSnapshot?,
        extraOutputBufferFrames: Int,
        outputQueueFrames: Int,
        inputExtraQueueFrames: Int,
        sampleRateHz: Int,
    ): AppAddedLatencyEstimate {
        val outputMeasured = outputCallbackCost?.sampleCount ?: 0L
        val inputMeasured = inputLoopCost?.sampleCount ?: 0L
        val confidence = resolveConfidence(outputMeasured, inputMeasured, inputLoopCost != null)
        return AppAddedLatencyEstimate(
            appAddedOutputMsEstimate =
                estimateAppAddedOutputMs(
                    outputCallbackCost = outputCallbackCost,
                    outputMeasured = outputMeasured,
                    extraOutputBufferFrames = extraOutputBufferFrames,
                    outputQueueFrames = outputQueueFrames,
                    sampleRateHz = sampleRateHz,
                ),
            appAddedInputMsEstimate =
                estimateAppAddedInputMs(
                    inputLoopCost = inputLoopCost,
                    inputMeasured = inputMeasured,
                    inputExtraQueueFrames = inputExtraQueueFrames,
                    sampleRateHz = sampleRateHz,
                ),
            confidence = confidence,
        )
    }

    fun callbackCpuLoadPercent(
        outputCallbackCost: AudioCallbackCostSnapshot?,
        sampleRateHz: Int,
    ): Double? = outputCallbackCost?.callbackCpuLoadPercent(sampleRateHz)

    fun callbackCpuLoadSafe(outputCallbackCost: AudioCallbackCostSnapshot?, sampleRateHz: Int): Boolean {
        val load = callbackCpuLoadPercent(outputCallbackCost, sampleRateHz) ?: return false
        return load < SAFE_CALLBACK_CPU_LOAD_PERCENT
    }

    private fun estimateAppAddedOutputMs(
        outputCallbackCost: AudioCallbackCostSnapshot?,
        outputMeasured: Long,
        extraOutputBufferFrames: Int,
        outputQueueFrames: Int,
        sampleRateHz: Int,
    ): Double? {
        if (outputCallbackCost == null || outputMeasured <= 0) {
            return null
        }
        val renderOverheadMs = outputCallbackCost.renderP95Us / 1000.0
        val hasAvoidableOutputQueue = extraOutputBufferFrames > 0 || outputQueueFrames > 0
        if (!hasAvoidableOutputQueue) {
            return renderOverheadMs
        }
        val outputBufferDelayMs = framesToMs(extraOutputBufferFrames, sampleRateHz)
        val outputQueueDelayMs = framesToMs(outputQueueFrames, sampleRateHz)
        return renderOverheadMs + outputBufferDelayMs + outputQueueDelayMs
    }

    private fun estimateAppAddedInputMs(
        inputLoopCost: AudioInputLoopCostSnapshot?,
        inputMeasured: Long,
        inputExtraQueueFrames: Int,
        sampleRateHz: Int,
    ): Double? {
        if (inputLoopCost == null || inputMeasured <= 0) {
            return null
        }
        val processingMs = inputLoopCost.processingP95Us / 1000.0
        if (inputExtraQueueFrames <= 0 || sampleRateHz <= 0) {
            return processingMs
        }
        return framesToMs(inputExtraQueueFrames, sampleRateHz) + processingMs
    }

    private fun framesToMs(frames: Int, sampleRateHz: Int): Double =
        if (frames > 0 && sampleRateHz > 0) {
            frames * 1000.0 / sampleRateHz.toDouble()
        } else {
            0.0
        }

    private fun resolveConfidence(
        outputSampleCount: Long,
        inputSampleCount: Long,
        inputPathActive: Boolean,
    ): AppAddedLatencyConfidence {
        val outputReady = outputSampleCount >= OUTPUT_SAMPLE_THRESHOLD
        val inputReady = !inputPathActive || inputSampleCount >= INPUT_SAMPLE_THRESHOLD
        return when {
            outputReady && inputReady -> AppAddedLatencyConfidence.MEASURED
            outputSampleCount > 0L || inputSampleCount > 0L -> AppAddedLatencyConfidence.PARTIAL
            else -> AppAddedLatencyConfidence.UNAVAILABLE
        }
    }
}
