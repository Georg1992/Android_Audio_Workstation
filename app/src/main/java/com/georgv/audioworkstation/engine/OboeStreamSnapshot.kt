package com.georgv.audioworkstation.engine

import com.georgv.audioworkstation.core.audio.latency.LatencyTimeConstants

data class OboeStreamSnapshot(
    val sampleRateHz: Int,
    val channelCount: Int,
    val framesPerBurst: Int,
    val bufferCapacityInFrames: Int,
    val bufferSizeInFrames: Int,
    val performanceMode: Int,
    val sharingMode: Int,
    val audioSessionId: Int,
) {
    fun bufferCapacityMs(): Long? =
        if (sampleRateHz > 0) {
            bufferCapacityInFrames * 1000L / sampleRateHz
        } else {
            null
        }

    fun burstMs(): Long? =
        if (sampleRateHz > 0) {
            framesPerBurst * 1000L / sampleRateHz
        } else {
            null
        }
}

data class PlaybackSessionTimings(
    val playbackArmSteadyNs: Long,
    val firstInputSampleSteadyNs: Long,
    val firstNonSilentOutputSteadyNs: Long,
    val firstAudibleOutputSteadyNs: Long,
    val deferEnabled: Boolean,
    val prerollFrames: Int,
    val ioBatchFrames: Int,
    val recordReadFrames: Int,
    val playbackArmTransportStartFrame: Long,
    val firstNonSilentTransportFrame: Long,
    val firstAudiblePeakTransportFrame: Long,
    val firstAudiblePeakMicro: Long,
    val openInputBeginSteadyNs: Long = 0L,
    val openInputDoneSteadyNs: Long = 0L,
    val oboeStreamOpenBeginSteadyNs: Long = 0L,
    val oboeStreamOpenDoneSteadyNs: Long = 0L,
    val oboeStreamStartDoneSteadyNs: Long = 0L,
    val firstOboeCallbackSteadyNs: Long = 0L,
) {
    fun armToFirstInputMs(): Long? =
        deltaMs(playbackArmSteadyNs, firstInputSampleSteadyNs)

    fun armToFirstNonSilentMs(): Long? =
        deltaMs(playbackArmSteadyNs, firstNonSilentOutputSteadyNs)

    fun armToFirstAudibleMs(): Long? =
        deltaMs(playbackArmSteadyNs, firstAudibleOutputSteadyNs)

    fun firstInputToFirstAudibleMs(): Long? =
        deltaMs(firstInputSampleSteadyNs, firstAudibleOutputSteadyNs)

    fun firstAudiblePeakLinear(): Float =
        firstAudiblePeakMicro.coerceAtLeast(0L).toFloat() / LatencyTimeConstants.MicroLinearScale

    fun transportMs(frame: Long, sampleRateHz: Int): Long? =
        if (frame < 0L || sampleRateHz <= 0) {
            null
        } else {
            frame * 1000L / sampleRateHz
        }

    fun playbackArmTransportStartMs(sampleRateHz: Int): Long? =
        transportMs(playbackArmTransportStartFrame, sampleRateHz)

    fun firstNonSilentTransportMs(sampleRateHz: Int): Long? =
        transportMs(firstNonSilentTransportFrame, sampleRateHz)

    fun firstAudibleTransportMs(sampleRateHz: Int): Long? =
        transportMs(firstAudiblePeakTransportFrame, sampleRateHz)

    private fun deltaMs(startNs: Long, endNs: Long): Long? =
        if (startNs > 0L && endNs > startNs) {
            (endNs - startNs) / LatencyTimeConstants.NanosecondsPerMillisecondLong
        } else {
            null
        }
}
