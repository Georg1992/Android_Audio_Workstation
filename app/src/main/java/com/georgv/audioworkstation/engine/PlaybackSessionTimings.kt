package com.georgv.audioworkstation.engine

import com.georgv.audioworkstation.core.audio.latency.LatencyTimeConstants

data class PlaybackSessionTimings(
    val playbackArmSteadyNs: Long,
    val firstInputSampleSteadyNs: Long,
    val firstNonSilentOutputSteadyNs: Long,
    val firstAudibleOutputSteadyNs: Long,
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

    private fun deltaMs(startNs: Long, endNs: Long): Long? =
        if (startNs > 0L && endNs > startNs) {
            (endNs - startNs) / LatencyTimeConstants.NanosecondsPerMillisecondLong
        } else {
            null
        }
}
