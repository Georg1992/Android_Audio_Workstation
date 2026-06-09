package com.georgv.audioworkstation.core.audio.latency

import com.georgv.audioworkstation.engine.OboeStreamCapabilityProbe
import com.georgv.audioworkstation.engine.SoftwareBufferProfile

data class AudioBufferAuditEntry(
    val name: String,
    val purpose: String,
    val sizeFrames: Int?,
    val sizeMs: Long?,
    val addsLatency: Boolean,
    val underrunSafetyOnly: Boolean,
    val minimumSafeFrames: Int?,
    val proposedOptimizedFrames: Int?,
    val xrunRiskIfReduced: String,
)

object AudioBufferAudit {
    fun entries(
        sampleRateHz: Int,
        profile: SoftwareBufferProfile?,
        outputProbe: OboeStreamCapabilityProbe?,
        inputProbe: OboeStreamCapabilityProbe?,
    ): List<AudioBufferAuditEntry> {
        val ringFrames =
            if (sampleRateHz > 0 && profile != null) {
                sampleRateHz * profile.ringDurationSeconds
            } else {
                null
            }
        val prerollFrames =
            if (sampleRateHz > 0 && profile != null) {
                sampleRateHz * profile.prerollWallMs / 1000
            } else {
                null
            }
        return prefetchEntries(
            sampleRateHz = sampleRateHz,
            profile = profile,
            ringFrames = ringFrames,
            prerollFrames = prerollFrames,
            outputProbe = outputProbe,
            inputProbe = inputProbe,
        ) + halBufferEntries(sampleRateHz, outputProbe, inputProbe)
    }

    private fun prefetchEntries(
        sampleRateHz: Int,
        profile: SoftwareBufferProfile?,
        ringFrames: Int?,
        prerollFrames: Int?,
        outputProbe: OboeStreamCapabilityProbe?,
        inputProbe: OboeStreamCapabilityProbe?,
    ): List<AudioBufferAuditEntry> =
        listOf(
            entry(
                name = "lane_ring_prefetch",
                purpose = "SPSC ring between I/O prefetch thread and Oboe render callback",
                sizeFrames = ringFrames,
                sampleRateHz = sampleRateHz,
                addsLatency = true,
                underrunSafetyOnly = true,
                minimumSafeFrames = outputProbe?.framesPerBurst?.times(2),
                proposedOptimizedFrames = outputProbe?.framesPerBurst?.times(4),
                xrunRiskIfReduced = "high_if_below_2x_burst",
            ),
            entry(
                name = "arm_preroll",
                purpose = "Decoder prefetch into ring at playback arm before first callback",
                sizeFrames = prerollFrames,
                sampleRateHz = sampleRateHz,
                addsLatency = true,
                underrunSafetyOnly = true,
                minimumSafeFrames = outputProbe?.framesPerBurst,
                proposedOptimizedFrames = outputProbe?.framesPerBurst?.times(2),
                xrunRiskIfReduced = "medium_first_playback_click",
            ),
            entry(
                name = "io_batch_prefetch",
                purpose = "Producer chunk size in ioLoop when refilling lane rings",
                sizeFrames = profile?.ioBatchFrames,
                sampleRateHz = sampleRateHz,
                addsLatency = false,
                underrunSafetyOnly = true,
                minimumSafeFrames = outputProbe?.framesPerBurst,
                proposedOptimizedFrames = outputProbe?.framesPerBurst?.times(2),
                xrunRiskIfReduced = "low_unless_starved_io_thread",
            ),
            entry(
                name = "input_blocking_read",
                purpose = "Blocking Oboe input read chunk on record thread",
                sizeFrames = profile?.inputReadFrames,
                sampleRateHz = sampleRateHz,
                addsLatency = true,
                underrunSafetyOnly = false,
                minimumSafeFrames = inputProbe?.framesPerBurst,
                proposedOptimizedFrames = inputProbe?.framesPerBurst,
                xrunRiskIfReduced = "low_cpu_overhead_may_rise",
            ),
        )

    private fun halBufferEntries(
        sampleRateHz: Int,
        outputProbe: OboeStreamCapabilityProbe?,
        inputProbe: OboeStreamCapabilityProbe?,
    ): List<AudioBufferAuditEntry> =
        listOf(
            entry(
                name = "oboe_output_buffer",
                purpose = "HAL output buffer chosen by Oboe/AAudio (not explicitly set by app)",
                sizeFrames = outputProbe?.bufferSizeInFrames,
                sampleRateHz = sampleRateHz,
                addsLatency = true,
                underrunSafetyOnly = true,
                minimumSafeFrames = outputProbe?.framesPerBurst?.times(2),
                proposedOptimizedFrames = outputProbe?.framesPerBurst?.times(2),
                xrunRiskIfReduced = "high_device_dependent",
            ),
            entry(
                name = "oboe_output_capacity",
                purpose = "HAL output buffer capacity (upper bound, often > bufferSize)",
                sizeFrames = outputProbe?.bufferCapacityInFrames,
                sampleRateHz = sampleRateHz,
                addsLatency = true,
                underrunSafetyOnly = true,
                minimumSafeFrames = outputProbe?.bufferSizeInFrames,
                proposedOptimizedFrames = outputProbe?.bufferSizeInFrames,
                xrunRiskIfReduced = "unknown_requires_setBufferSizeInFrames_probe",
            ),
            entry(
                name = "oboe_input_buffer",
                purpose = "HAL input buffer chosen by Oboe/AAudio",
                sizeFrames = inputProbe?.bufferSizeInFrames,
                sampleRateHz = sampleRateHz,
                addsLatency = true,
                underrunSafetyOnly = true,
                minimumSafeFrames = inputProbe?.framesPerBurst?.times(2),
                proposedOptimizedFrames = inputProbe?.framesPerBurst?.times(2),
                xrunRiskIfReduced = "high_device_dependent",
            ),
            entry(
                name = "output_callback_block",
                purpose = "Oboe onAudioReady numFrames (device/HAL chosen)",
                sizeFrames = outputProbe?.blockFrames?.takeIf { it > 0 },
                sampleRateHz = sampleRateHz,
                addsLatency = true,
                underrunSafetyOnly = true,
                minimumSafeFrames = outputProbe?.framesPerBurst,
                proposedOptimizedFrames = outputProbe?.framesPerBurst,
                xrunRiskIfReduced = "not_app_controlled",
            ),
        )

    private fun entry(
        name: String,
        purpose: String,
        sizeFrames: Int?,
        sampleRateHz: Int,
        addsLatency: Boolean,
        underrunSafetyOnly: Boolean,
        minimumSafeFrames: Int?,
        proposedOptimizedFrames: Int?,
        xrunRiskIfReduced: String,
    ): AudioBufferAuditEntry =
        AudioBufferAuditEntry(
            name = name,
            purpose = purpose,
            sizeFrames = sizeFrames,
            sizeMs = framesToMs(sizeFrames, sampleRateHz),
            addsLatency = addsLatency,
            underrunSafetyOnly = underrunSafetyOnly,
            minimumSafeFrames = minimumSafeFrames,
            proposedOptimizedFrames = proposedOptimizedFrames,
            xrunRiskIfReduced = xrunRiskIfReduced,
        )

    private fun framesToMs(frames: Int?, sampleRateHz: Int): Long? =
        if (frames == null || frames <= 0 || sampleRateHz <= 0) {
            null
        } else {
            frames * 1000L / sampleRateHz
        }
}
