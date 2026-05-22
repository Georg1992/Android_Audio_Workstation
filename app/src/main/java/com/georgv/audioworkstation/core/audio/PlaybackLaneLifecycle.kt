package com.georgv.audioworkstation.core.audio

/** Mirrors native [PlaybackLaneLifecycle] ordinals (HJ.2). */
enum class PlaybackLaneLifecycle {
    Inactive,
    Preparing,
    ReadyToCommit,
    Active,
    Cancelled,
    Exhausted,
}
