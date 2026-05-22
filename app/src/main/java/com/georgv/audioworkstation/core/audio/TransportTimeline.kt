package com.georgv.audioworkstation.core.audio

/**
 * Maps native [transportFrame] to milliseconds using the same integer math as
 * [com.georgv.audioworkstation.engine.NativeEngine.transportPositionMs].
 */
fun transportFrameToMs(frame: Long, sampleRateHz: Int): Long {
    if (sampleRateHz <= 0) return 0L
    return (frame * 1000L) / sampleRateHz.toLong()
}

/** @deprecated Use [transportFrameToMs]. */
@Deprecated("Use transportFrameToMs", ReplaceWith("transportFrameToMs(frame, sampleRateHz)"))
fun masterPlaybackFrameToMs(frame: Long, sampleRateHz: Int): Long = transportFrameToMs(frame, sampleRateHz)
