package com.georgv.audioworkstation.core.audio

/**
 * Pure transport ↔ timeline mapping. No Android APIs, no engine state.
 */
object AudioTimelineMapper {
    /** Matches native [transportPositionMs]: `(frame * 1000) / sampleRate`. */
    fun transportFrameToMs(frame: Long, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0L
        return (frame * 1000L) / sampleRate.toLong()
    }

    /** Matches native `playbackStartFrameFromMs`: `(sampleRate * ms) / 1000`, zero when ms ≤ 0. */
    fun transportMsToFrame(ms: Long, sampleRate: Int): Long {
        if (ms <= 0L || sampleRate <= 0) return 0L
        return sampleRate.toLong() * ms / 1000L
    }
}
