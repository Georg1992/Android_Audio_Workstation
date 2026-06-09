package com.georgv.audioworkstation.core.audio.capability.audit

import com.georgv.audioworkstation.engine.AudioCallbackCostSnapshot
import com.georgv.audioworkstation.engine.AudioInputLoopCostSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLatencyEstimateCalculatorTest {
    @Test
    fun estimate_noExtraQueue_usesP95RenderAndProcessing() {
        val outputCost = sampleOutputCost(renderP95Us = 420L)
        val inputCost = sampleInputCost(processingP95Us = 180L)

        val estimate =
            AppLatencyEstimateCalculator.estimate(
                outputCallbackCost = outputCost,
                inputLoopCost = inputCost,
                extraOutputBufferFrames = 0,
                outputQueueFrames = 0,
                inputExtraQueueFrames = 0,
                sampleRateHz = 48_000,
            )

        assertEquals(0.420, estimate.appAddedOutputMsEstimate!!, 0.001)
        assertEquals(0.180, estimate.appAddedInputMsEstimate!!, 0.001)
        assertEquals(AppAddedLatencyConfidence.MEASURED, estimate.confidence)
    }

    @Test
    fun estimate_extraOutputBuffer_addsBufferDelayToOutputEstimate() {
        val outputCost = sampleOutputCost(renderP95Us = 300L)

        val estimate =
            AppLatencyEstimateCalculator.estimate(
                outputCallbackCost = outputCost,
                inputLoopCost = null,
                extraOutputBufferFrames = 256,
                outputQueueFrames = 0,
                inputExtraQueueFrames = 0,
                sampleRateHz = 48_000,
            )

        assertEquals(0.300 + (256 * 1000.0 / 48_000.0), estimate.appAddedOutputMsEstimate!!, 0.001)
    }

    @Test
    fun estimate_noSamples_returnsUnavailableConfidence() {
        val estimate =
            AppLatencyEstimateCalculator.estimate(
                outputCallbackCost = sampleOutputCost(renderP95Us = 0L).copy(sampleCount = 0),
                inputLoopCost = sampleInputCost(processingP95Us = 0L).copy(sampleCount = 0),
                extraOutputBufferFrames = 0,
                outputQueueFrames = 0,
                inputExtraQueueFrames = 0,
                sampleRateHz = 48_000,
            )

        assertNull(estimate.appAddedOutputMsEstimate)
        assertNull(estimate.appAddedInputMsEstimate)
        assertEquals(AppAddedLatencyConfidence.UNAVAILABLE, estimate.confidence)
    }

    @Test
    fun callbackCpuLoadPercent_matchesBudgetRatio() {
        val outputCost =
            sampleOutputCost(
                callbackFrames = 480,
                callbackAvgUs = 500L,
            )
        val load = AppLatencyEstimateCalculator.callbackCpuLoadPercent(outputCost, 48_000)
        assertEquals(5.0, load!!, 0.01)
    }

    private fun sampleOutputCost(
        callbackFrames: Int = 480,
        renderP95Us: Long = 420L,
        callbackAvgUs: Long = 400L,
        sampleCount: Long = 64L,
    ): AudioCallbackCostSnapshot =
        AudioCallbackCostSnapshot(
            callbackFrames = callbackFrames,
            sampleCount = sampleCount,
            callbackMinUs = 100L,
            callbackAvgUs = callbackAvgUs,
            callbackMaxUs = 900L,
            callbackP95Us = callbackAvgUs + 50L,
            renderMinUs = 80L,
            renderAvgUs = 350L,
            renderMaxUs = 800L,
            renderP95Us = renderP95Us,
            xRunCount = 0,
        )

    private fun sampleInputCost(
        processingP95Us: Long = 180L,
        sampleCount: Long = 32L,
    ): AudioInputLoopCostSnapshot =
        AudioInputLoopCostSnapshot(
            readFrames = 960,
            sampleCount = sampleCount,
            readBlockingMinUs = 1_000L,
            readBlockingAvgUs = 4_000L,
            readBlockingMaxUs = 8_000L,
            readBlockingP95Us = 7_000L,
            processingMinUs = 50L,
            processingAvgUs = 120L,
            processingMaxUs = 250L,
            processingP95Us = processingP95Us,
        )
}
