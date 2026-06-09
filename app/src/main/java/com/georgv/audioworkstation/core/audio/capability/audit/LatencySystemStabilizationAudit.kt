package com.georgv.audioworkstation.core.audio.capability.audit

import android.util.Log
import com.georgv.audioworkstation.core.audio.capability.DeviceLatencySummaryBuilder
import com.georgv.audioworkstation.core.audio.capability.ResolvedAudioCapability
import com.georgv.audioworkstation.core.audio.latency.AudioLivePathType
import com.georgv.audioworkstation.engine.NativeEngine
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LatencySystemStabilizationAudit @Inject constructor(
    private val nativeEngine: NativeEngine,
) {
    fun audit(
        resolved: ResolvedAudioCapability,
        pathType: AudioLivePathType,
    ) {
        val profile = resolved.profile
        val completeness = CapabilityCompletenessEvaluator.evaluate(profile)
        val appAudit = AppLatencyAuditBuilder.build(nativeEngine, pathType, profile)
        val verdict =
            LatencyFloorVerdictEvaluator.evaluate(
                profile = profile,
                resolved = resolved,
                appAudit = appAudit,
                completeness = completeness,
            )
        val sampleRateHz = profile?.sampleRate?.takeIf { it > 0 } ?: 44_100

        LatencyStabilizationAuditLog.logCompleteness(completeness)
        appAudit.outputCallbackCost?.let {
            LatencyStabilizationAuditLog.logOutputCallbackCost(it, sampleRateHz)
        }
        appAudit.inputLoopCost?.let {
            LatencyStabilizationAuditLog.logInputLoopCost(it, sampleRateHz)
        }
        LatencyStabilizationAuditLog.logAppLatency(appAudit)
        LatencyStabilizationAuditLog.logFloorVerdict(verdict)
    }
}

internal object LatencyStabilizationAuditLog {
    private const val TAG = "AudioSyncDiag"

    fun logCompleteness(result: CapabilityCompletenessResult) {
        Log.i(
            TAG,
            "[AUDIO_CAPABILITY_COMPLETENESS] " +
                "outputStreamConfigCaptured=${result.outputStreamConfigCaptured} " +
                "inputStreamConfigCaptured=${result.inputStreamConfigCaptured} " +
                "outputHardwareFloorCaptured=${result.outputHardwareFloorCaptured} " +
                "trueCaptureDelayCaptured=${result.trueCaptureDelayCaptured} " +
                "roundTripCaptured=${result.roundTripCaptured} " +
                "jitterCaptured=${result.jitterCaptured} " +
                "startupMetricsCaptured=${result.startupMetricsCaptured} " +
                "routeKey=${result.routeKey} " +
                "sampleRate=${result.sampleRate} " +
                "missingFields=${result.missingFieldsLabel}",
        )
    }

    fun logOutputCallbackCost(
        cost: com.georgv.audioworkstation.engine.AudioCallbackCostSnapshot,
        sampleRateHz: Int,
    ) {
        val budgetMs = cost.callbackBudgetMs(sampleRateHz)
        val cpuLoad = cost.callbackCpuLoadPercent(sampleRateHz)
        Log.i(
            TAG,
            "[AUDIO_CALLBACK_COST] " +
                "direction=output " +
                "callbackFrames=${cost.callbackFrames} " +
                "callbackBudgetMs=${formatOptionalMs(budgetMs)} " +
                "avgCallbackDurationUs=${cost.callbackAvgUs} " +
                "p95CallbackDurationUs=${cost.callbackP95Us} " +
                "maxCallbackDurationUs=${cost.callbackMaxUs} " +
                "avgRenderDurationUs=${cost.renderAvgUs} " +
                "p95RenderDurationUs=${cost.renderP95Us} " +
                "callbackCpuLoadPercent=${formatOptionalMs(cpuLoad)} " +
                "xrunCount=${cost.xRunCount}",
        )
    }

    fun logInputLoopCost(
        cost: com.georgv.audioworkstation.engine.AudioInputLoopCostSnapshot,
        sampleRateHz: Int,
    ) {
        val budgetMs = cost.readBlockBudgetMs(sampleRateHz)
        Log.i(
            TAG,
            "[AUDIO_INPUT_LOOP_COST] " +
                "readFrames=${cost.readFrames} " +
                "readBlockBudgetMs=${formatOptionalMs(budgetMs)} " +
                "avgReadBlockingUs=${cost.readBlockingAvgUs} " +
                "p95ReadBlockingUs=${cost.readBlockingP95Us} " +
                "maxReadBlockingUs=${cost.readBlockingMaxUs} " +
                "avgProcessingUs=${cost.processingAvgUs} " +
                "p95ProcessingUs=${cost.processingP95Us} " +
                "maxProcessingUs=${cost.processingMaxUs}",
        )
    }

    fun logAppLatency(result: AppLatencyAuditResult) {
        Log.i(
            TAG,
            "[APP_LATENCY_AUDIT] " +
                "pathType=${result.pathType} " +
                "outputQueueFrames=${result.outputQueueFrames} " +
                "renderInCallback=${result.renderInCallback} " +
                "extraOutputBufferFrames=${result.extraOutputBufferFrames} " +
                "outputPrerollFrames=${result.outputPrerollFrames} " +
                "outputBufferDelayMs=${formatMs(result.outputBufferDelayMs)} " +
                "inputReadFrames=${result.inputReadFrames} " +
                "inputReadBlockMs=${formatMs(result.inputReadBlockMs)} " +
                "inputExtraQueueFrames=${result.inputExtraQueueFrames} " +
                "inputBufferDelayMs=${formatMs(result.inputBufferDelayMs)} " +
                "appAddedOutputMsEstimate=${formatOptionalMs(result.appAddedOutputMsEstimate)} " +
                "appAddedInputMsEstimate=${formatOptionalMs(result.appAddedInputMsEstimate)} " +
                "appAddedLatencyConfidence=${result.appAddedLatencyConfidence} " +
                "callbackCpuLoadPercent=${formatOptionalMs(result.callbackCpuLoadPercent)} " +
                "wavWriteAsyncDoesNotBlockCapture=${result.wavWriteAsyncDoesNotBlockCapture} " +
                "compensationDisabled=${result.compensationDisabled} " +
                "startupDelayExcludedFromCompensation=${result.startupDelayExcludedFromCompensation} " +
                "placementUsesStartupDelay=${result.placementUsesStartupDelay} " +
                "captureDelaySource=${result.captureDelaySource}",
        )
    }

    fun logFloorVerdict(result: LatencyFloorVerdictResult) {
        Log.i(
            TAG,
            "[LATENCY_FLOOR_VERDICT] " +
                "routeKey=${result.routeKey} " +
                "outputFloorMs=${formatOptionalMs(result.outputFloorMs)} " +
                "inputCaptureDelayMs=${formatOptionalMs(result.inputCaptureDelayMs)} " +
                "roundTripMs=${formatOptionalMs(result.roundTripMs)} " +
                "appAddedOutputMsEstimate=${formatOptionalMs(result.appAddedOutputMsEstimate)} " +
                "appAddedInputMsEstimate=${formatOptionalMs(result.appAddedInputMsEstimate)} " +
                "appAddedLatencyConfidence=${result.appAddedLatencyConfidence} " +
                "lowLatencyOutputGranted=${result.lowLatencyOutputGranted} " +
                "lowLatencyInputGranted=${result.lowLatencyInputGranted} " +
                "verdict=${result.verdict} " +
                "reason=${result.reason}",
        )
    }

    private fun formatMs(value: Double): String = String.format(Locale.US, "%.3f", value)

    private fun formatOptionalMs(value: Double?): String =
        if (value != null && value.isFinite() && value >= 0.0) {
            formatMs(value)
        } else {
            "n/a"
        }
}
