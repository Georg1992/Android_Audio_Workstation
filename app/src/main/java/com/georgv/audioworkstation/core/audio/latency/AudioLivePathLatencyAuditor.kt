package com.georgv.audioworkstation.core.audio.latency

import com.georgv.audioworkstation.engine.OboeStreamCapabilityProbe
import com.georgv.audioworkstation.engine.PlaybackSessionTimings
import com.georgv.audioworkstation.engine.SoftwareBufferProfile
import kotlin.math.max

object AudioLivePathLatencyAuditor {
    fun buildBreakdown(
        pathType: AudioLivePathType,
        routeKey: String,
        sampleRateHz: Int,
        timings: PlaybackSessionTimings?,
        outputProbe: OboeStreamCapabilityProbe?,
        inputProbe: OboeStreamCapabilityProbe?,
        bufferProfile: SoftwareBufferProfile?,
    ): AudioLivePathLatencyBreakdown {
        val anchorNs = timings?.playbackArmSteadyNs ?: 0L
        val streamOpenMs = deltaMs(anchorNs, timings?.oboeStreamOpenDoneSteadyNs)
        val streamStartMs = deltaMs(anchorNs, timings?.oboeStreamStartDoneSteadyNs)
        val firstCallbackMs = deltaMs(anchorNs, timings?.firstOboeCallbackSteadyNs)
        val firstInputMs = timings?.armToFirstInputMs()
        val firstNonSilentOutputMs = timings?.armToFirstNonSilentMs()
        val deferredGateMs =
            if (timings?.deferEnabled == true) {
                firstInputMs
            } else {
                0L
            }
        val prerollMs = framesToMs(timings?.prerollFrames, sampleRateHz)
        val ioPrefetchMs = framesToMs(timings?.ioBatchFrames, sampleRateHz)
        val inputReadMs = framesToMs(timings?.recordReadFrames, sampleRateHz)
        val outputHalMs = estimateHalLatencyMs(outputProbe)
        val inputHalMs = estimateHalLatencyMs(inputProbe)
        val appAddedMs =
            listOfNotNull(
                prerollMs,
                ioPrefetchMs?.let { it / 2 },
                inputReadMs?.let { it / 2 },
                deferredGateMs?.takeIf { it > 0L },
            ).sum()
        val hardwareMs = outputHalMs + inputHalMs
        val totalMs = hardwareMs + appAddedMs

        val notes =
            buildString {
                append("path=$pathType ")
                append("defer=${timings?.deferEnabled == true} ")
                append("inputOpenMs=${deltaMs(anchorNs, timings?.openInputDoneSteadyNs)} ")
                append("bufferProfile=${bufferProfile != null} ")
                when (pathType) {
                    AudioLivePathType.LIVE_MONITOR,
                    AudioLivePathType.LOOP_OVERDUB,
                    -> append("future_path_not_implemented")
                    else -> append("audit_read_only")
                }
            }

        return AudioLivePathLatencyBreakdown(
            pathType = pathType,
            routeKey = routeKey,
            streamOpenMs = streamOpenMs,
            streamStartMs = streamStartMs,
            firstCallbackMs = firstCallbackMs,
            firstInputMs = firstInputMs,
            firstNonSilentOutputMs = firstNonSilentOutputMs,
            deferredGateMs = deferredGateMs,
            decoderOpenMs = null,
            prerollMs = prerollMs,
            ioPrefetchMs = ioPrefetchMs,
            appAddedLatencyMs = appAddedMs,
            estimatedHardwareLatencyMs = hardwareMs,
            estimatedTotalLiveLatencyMs = totalMs,
            notes = notes.trim(),
        )
    }

    fun estimateHalLatencyMs(probe: OboeStreamCapabilityProbe?): Long {
        if (probe == null || probe.sampleRateHz <= 0) return 0L
        val burstMs = probe.framesPerBurst * 1000L / probe.sampleRateHz
        val capacityMs = probe.bufferCapacityInFrames * 1000L / probe.bufferRateHzSafe()
        return burstMs * 2 + capacityMs / 2
    }

    private fun OboeStreamCapabilityProbe.bufferRateHzSafe(): Int = max(sampleRateHz, 1)

    private fun deltaMs(anchorNs: Long, stageNs: Long?): Long? =
        if (anchorNs > 0L && stageNs != null && stageNs > anchorNs) {
            (stageNs - anchorNs) / LatencyTimeConstants.NanosecondsPerMillisecondLong
        } else {
            null
        }

    private fun framesToMs(frames: Int?, sampleRateHz: Int): Long? =
        if (frames == null || frames <= 0 || sampleRateHz <= 0) {
            null
        } else {
            frames * 1000L / sampleRateHz
        }
}
