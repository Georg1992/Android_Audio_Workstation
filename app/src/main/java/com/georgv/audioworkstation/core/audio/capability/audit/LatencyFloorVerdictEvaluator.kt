package com.georgv.audioworkstation.core.audio.capability.audit

import com.georgv.audioworkstation.core.audio.capability.DeviceAudioCapabilityClassifier
import com.georgv.audioworkstation.core.audio.capability.DeviceAudioCapabilityProfile
import com.georgv.audioworkstation.core.audio.capability.ResolvedAudioCapability
import java.util.Locale

enum class LatencyFloorVerdictType {
    HARDWARE_LIMITED,
    APP_ADDS_LATENCY,
    INSUFFICIENT_DATA,
}

data class LatencyFloorVerdictResult(
    val routeKey: String,
    val outputFloorMs: Double?,
    val inputCaptureDelayMs: Double?,
    val roundTripMs: Double?,
    val appAddedOutputMsEstimate: Double?,
    val appAddedInputMsEstimate: Double?,
    val appAddedLatencyConfidence: AppAddedLatencyConfidence,
    val lowLatencyOutputGranted: Boolean,
    val lowLatencyInputGranted: Boolean,
    val verdict: LatencyFloorVerdictType,
    val reason: String,
)

object LatencyFloorVerdictEvaluator {
    private const val NEAR_ZERO_MS = 2.0

    fun evaluate(
        profile: DeviceAudioCapabilityProfile?,
        resolved: ResolvedAudioCapability,
        appAudit: AppLatencyAuditResult,
        completeness: CapabilityCompletenessResult,
    ): LatencyFloorVerdictResult {
        val outputFloorMs = resolved.outputLatencyMs
        val inputCaptureDelayMs = resolved.inputCaptureDelayMs
        val roundTripMs = resolved.roundTripMs
        val lowLatencyOutputGranted = resolved.lowLatencyOutputPathGranted
        val lowLatencyInputGranted = profile?.input?.performanceModeGranted ?: false

        if (!hasRequiredFloorData(completeness)) {
            return baseVerdict(
                resolved = resolved,
                appAudit = appAudit,
                outputFloorMs = outputFloorMs,
                inputCaptureDelayMs = inputCaptureDelayMs,
                roundTripMs = roundTripMs,
                lowLatencyOutputGranted = lowLatencyOutputGranted,
                lowLatencyInputGranted = lowLatencyInputGranted,
                verdict = LatencyFloorVerdictType.INSUFFICIENT_DATA,
                reason = "missing_required_profile_fields:${completeness.missingFieldsLabel}",
            )
        }

        if (appAddsAvoidableLatency(appAudit)) {
            return baseVerdict(
                resolved = resolved,
                appAudit = appAudit,
                outputFloorMs = outputFloorMs,
                inputCaptureDelayMs = inputCaptureDelayMs,
                roundTripMs = roundTripMs,
                lowLatencyOutputGranted = lowLatencyOutputGranted,
                lowLatencyInputGranted = lowLatencyInputGranted,
                verdict = LatencyFloorVerdictType.APP_ADDS_LATENCY,
                reason = appAddsLatencyReason(appAudit),
            )
        }

        return baseVerdict(
            resolved = resolved,
            appAudit = appAudit,
            outputFloorMs = outputFloorMs,
            inputCaptureDelayMs = inputCaptureDelayMs,
            roundTripMs = roundTripMs,
            lowLatencyOutputGranted = lowLatencyOutputGranted,
            lowLatencyInputGranted = lowLatencyInputGranted,
            verdict = LatencyFloorVerdictType.HARDWARE_LIMITED,
            reason = hardwareLimitedReason(profile, appAudit, lowLatencyOutputGranted),
        )
    }

    private fun baseVerdict(
        resolved: ResolvedAudioCapability,
        appAudit: AppLatencyAuditResult,
        outputFloorMs: Double?,
        inputCaptureDelayMs: Double?,
        roundTripMs: Double?,
        lowLatencyOutputGranted: Boolean,
        lowLatencyInputGranted: Boolean,
        verdict: LatencyFloorVerdictType,
        reason: String,
    ): LatencyFloorVerdictResult =
        LatencyFloorVerdictResult(
            routeKey = resolved.routeKey,
            outputFloorMs = outputFloorMs,
            inputCaptureDelayMs = inputCaptureDelayMs,
            roundTripMs = roundTripMs,
            appAddedOutputMsEstimate = appAudit.appAddedOutputMsEstimate,
            appAddedInputMsEstimate = appAudit.appAddedInputMsEstimate,
            appAddedLatencyConfidence = appAudit.appAddedLatencyConfidence,
            lowLatencyOutputGranted = lowLatencyOutputGranted,
            lowLatencyInputGranted = lowLatencyInputGranted,
            verdict = verdict,
            reason = reason,
        )

    private fun hasRequiredFloorData(completeness: CapabilityCompletenessResult): Boolean =
        completeness.outputStreamConfigCaptured &&
            completeness.outputHardwareFloorCaptured &&
            completeness.trueCaptureDelayCaptured &&
            completeness.roundTripCaptured

    private fun appAddsAvoidableLatency(appAudit: AppLatencyAuditResult): Boolean =
        appAudit.extraOutputBufferFrames > 0 ||
            appAudit.outputQueueFrames > 0 ||
            appAudit.inputExtraQueueFrames > 0 ||
            appAudit.outputBufferDelayMs > NEAR_ZERO_MS ||
            appAudit.inputBufferDelayMs > NEAR_ZERO_MS ||
            !callbackCpuLoadSafe(appAudit)

    private fun callbackCpuLoadSafe(appAudit: AppLatencyAuditResult): Boolean {
        val load = appAudit.callbackCpuLoadPercent ?: return true
        return load < 80.0
    }

    private fun appAddsLatencyReason(appAudit: AppLatencyAuditResult): String =
        buildString {
            if (appAudit.extraOutputBufferFrames > 0) {
                append("extra_output_buffer_frames=${appAudit.extraOutputBufferFrames}")
            }
            if (appAudit.outputBufferDelayMs > NEAR_ZERO_MS) {
                if (isNotEmpty()) append(';')
                append("outputBufferDelayMs=${formatMs(appAudit.outputBufferDelayMs)}")
            }
            if (appAudit.inputBufferDelayMs > NEAR_ZERO_MS) {
                if (isNotEmpty()) append(';')
                append("inputBufferDelayMs=${formatMs(appAudit.inputBufferDelayMs)}")
            }
            if (!callbackCpuLoadSafe(appAudit)) {
                if (isNotEmpty()) append(';')
                append("callbackCpuLoadPercent=${formatMs(appAudit.callbackCpuLoadPercent ?: 0.0)}")
            }
        }.ifEmpty { "app_software_delay_detected" }

    private fun hardwareLimitedReason(
        profile: DeviceAudioCapabilityProfile?,
        appAudit: AppLatencyAuditResult,
        lowLatencyOutputGranted: Boolean,
    ): String {
        val outputMs = profile?.let { DeviceAudioCapabilityClassifier.effectiveOutputLatencyMs(it) }
        return buildString {
            append("steady_state_output_hal_floor")
            if (outputMs != null) {
                append('=')
                append(formatMs(outputMs))
                append("ms")
            }
            append(';')
            append("lowLatencyOutputGranted=$lowLatencyOutputGranted")
            append(';')
            append("app_added_output_estimate=")
            append(formatOptionalMs(appAudit.appAddedOutputMsEstimate))
            append("ms")
            append(';')
            append("app_added_input_estimate=")
            append(formatOptionalMs(appAudit.appAddedInputMsEstimate))
            append("ms")
            append(';')
            append("no_extra_output_queue=${appAudit.extraOutputBufferFrames == 0 && appAudit.outputQueueFrames == 0}")
            append(';')
            append("no_input_queue=${appAudit.inputExtraQueueFrames == 0}")
            append(';')
            append("render_in_callback=${appAudit.renderInCallback}")
            appAudit.callbackCpuLoadPercent?.let { load ->
                append(';')
                append("callbackCpuLoadPercent=${formatMs(load)}")
            }
        }
    }

    private fun formatMs(value: Double): String = String.format(Locale.US, "%.3f", value)

    private fun formatOptionalMs(value: Double?): String =
        if (value != null && value.isFinite() && value >= 0.0) {
            formatMs(value)
        } else {
            "n/a"
        }
}
