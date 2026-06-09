package com.georgv.audioworkstation.core.audio.latency

enum class AudioLivePathType {
    PLAYBACK_ONLY,
    RECORDING_ONLY,
    OVERDUB,
    /** Not implemented yet — audit scaffold only. */
    LIVE_MONITOR,
    /** Not implemented yet — audit scaffold only. */
    LOOP_OVERDUB,
}
