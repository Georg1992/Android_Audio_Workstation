package com.georgv.audioworkstation.core.audio.capability.audit

import com.georgv.audioworkstation.core.audio.capability.ResolvedAudioCapability
import com.georgv.audioworkstation.core.audio.capability.sampleCapabilityProfile
import com.georgv.audioworkstation.core.audio.latency.AudioLivePathType
import com.georgv.audioworkstation.engine.AudioCallbackCostSnapshot
import com.georgv.audioworkstation.engine.AudioInputLoopCostSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyFloorVerdictEvaluatorTest {
    @Test
    fun evaluate_completeProfileSmallAppOverhead_isHardwareLimited() {
        val profile = sampleCapabilityProfile()
        val resolved = resolvedFrom(profile)
        val completeness = CapabilityCompletenessEvaluator.evaluate(profile)
        val appAudit = hardwareLimitedAppAudit()

        val verdict =
            LatencyFloorVerdictEvaluator.evaluate(
                profile = profile,
                resolved = resolved,
                appAudit = appAudit,
                completeness = completeness,
            )

        assertEquals(LatencyFloorVerdictType.HARDWARE_LIMITED, verdict.verdict)
        assertEquals(12.0, verdict.outputFloorMs!!, 0.001)
        assertEquals(83.0, verdict.inputCaptureDelayMs!!, 0.001)
        assertEquals(0.420, verdict.appAddedOutputMsEstimate!!, 0.001)
        assertTrue(verdict.reason.contains("app_added_output_estimate=0.420ms"))
        assertTrue(verdict.reason.contains("render_in_callback=true"))
    }

    @Test
    fun evaluate_extraOutputBuffer_isAppAddsLatency() {
        val profile = sampleCapabilityProfile()
        val resolved = resolvedFrom(profile)
        val completeness = CapabilityCompletenessEvaluator.evaluate(profile)
        val appAudit =
            hardwareLimitedAppAudit().copy(
                extraOutputBufferFrames = 256,
                outputBufferDelayMs = 5.8,
            )

        val verdict =
            LatencyFloorVerdictEvaluator.evaluate(
                profile = profile,
                resolved = resolved,
                appAudit = appAudit,
                completeness = completeness,
            )

        assertEquals(LatencyFloorVerdictType.APP_ADDS_LATENCY, verdict.verdict)
    }

    @Test
    fun evaluate_highCallbackCpuLoad_isAppAddsLatency() {
        val profile = sampleCapabilityProfile()
        val resolved = resolvedFrom(profile)
        val completeness = CapabilityCompletenessEvaluator.evaluate(profile)
        val appAudit =
            hardwareLimitedAppAudit().copy(
                callbackCpuLoadPercent = 92.0,
            )

        val verdict =
            LatencyFloorVerdictEvaluator.evaluate(
                profile = profile,
                resolved = resolved,
                appAudit = appAudit,
                completeness = completeness,
            )

        assertEquals(LatencyFloorVerdictType.APP_ADDS_LATENCY, verdict.verdict)
        assertTrue(verdict.reason.contains("callbackCpuLoadPercent"))
    }

    @Test
    fun evaluate_missingCaptureDelay_isInsufficientData() {
        val profile =
            sampleCapabilityProfile().copy(
                calibration = sampleCapabilityProfile().calibration.copy(
                    estimatedTrueCaptureDelayMs = null,
                    measuredRoundTripMs = null,
                ),
            )
        val resolved =
            resolvedFrom(profile).copy(
                inputCaptureDelayMs = null,
                roundTripMs = null,
            )
        val completeness = CapabilityCompletenessEvaluator.evaluate(profile)
        val appAudit = hardwareLimitedAppAudit()

        val verdict =
            LatencyFloorVerdictEvaluator.evaluate(
                profile = profile,
                resolved = resolved,
                appAudit = appAudit,
                completeness = completeness,
            )

        assertEquals(LatencyFloorVerdictType.INSUFFICIENT_DATA, verdict.verdict)
    }

    private fun resolvedFrom(profile: com.georgv.audioworkstation.core.audio.capability.DeviceAudioCapabilityProfile) =
        ResolvedAudioCapability(
            profile = profile,
            profileId = profile.profileId,
            routeKey = profile.routeKey,
            sampleRate = profile.sampleRate,
            outputLatencyMs = 12.0,
            inputCaptureDelayMs = profile.calibration.estimatedTrueCaptureDelayMs,
            inputHalLatencyMs = profile.input.halReportedLatencyMs,
            roundTripMs = profile.calibration.measuredRoundTripMs,
            jitterMs = profile.calibration.measuredJitterMs,
            confidence = profile.overallConfidence(),
            lowLatencyOutputPathGranted = profile.output.performanceModeGranted,
            lowLatencyInputPathGranted = profile.input.performanceModeGranted,
            profileState = profile.derived.profileState,
            routeUnchanged = true,
            validation = profile.validation,
            warnings = emptyList(),
            dataComplete = true,
        )

    private fun hardwareLimitedAppAudit() =
        AppLatencyAuditResult(
            outputQueueFrames = 0,
            renderInCallback = true,
            extraOutputBufferFrames = 0,
            outputPrerollFrames = 512,
            outputBufferDelayMs = 0.0,
            inputReadFrames = 192,
            inputReadBlockMs = 4.35,
            inputExtraQueueFrames = 0,
            inputBufferDelayMs = 0.0,
            wavWriteAsyncDoesNotBlockCapture = true,
            compensationDisabled = true,
            startupDelayExcludedFromCompensation = true,
            placementUsesStartupDelay = true,
            captureDelaySource = "calibration",
            pathType = AudioLivePathType.OVERDUB,
            outputCallbackCost = sampleOutputCost(),
            inputLoopCost = sampleInputCost(),
            appAddedOutputMsEstimate = 0.420,
            appAddedInputMsEstimate = 0.180,
            appAddedLatencyConfidence = AppAddedLatencyConfidence.MEASURED,
            callbackCpuLoadPercent = 12.0,
        )

    private fun sampleOutputCost(): AudioCallbackCostSnapshot =
        AudioCallbackCostSnapshot(
            callbackFrames = 480,
            sampleCount = 64,
            callbackMinUs = 100L,
            callbackAvgUs = 400L,
            callbackMaxUs = 900L,
            callbackP95Us = 450L,
            renderMinUs = 80L,
            renderAvgUs = 350L,
            renderMaxUs = 800L,
            renderP95Us = 420L,
            xRunCount = 0,
        )

    private fun sampleInputCost(): AudioInputLoopCostSnapshot =
        AudioInputLoopCostSnapshot(
            readFrames = 960,
            sampleCount = 32,
            readBlockingMinUs = 1_000L,
            readBlockingAvgUs = 4_000L,
            readBlockingMaxUs = 8_000L,
            readBlockingP95Us = 7_000L,
            processingMinUs = 50L,
            processingAvgUs = 120L,
            processingMaxUs = 250L,
            processingP95Us = 180L,
        )
}
