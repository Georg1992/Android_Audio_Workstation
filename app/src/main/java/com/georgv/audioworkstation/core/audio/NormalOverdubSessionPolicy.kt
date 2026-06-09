package com.georgv.audioworkstation.core.audio

/** Whether a recording session qualifies for normal (non-loop) overdub latency compensation. */
fun isNormalOverdubRecording(
    overdubLaneCount: Int,
    anyLoopEnabledInBacking: Boolean,
): Boolean = overdubLaneCount > 0 && !anyLoopEnabledInBacking
