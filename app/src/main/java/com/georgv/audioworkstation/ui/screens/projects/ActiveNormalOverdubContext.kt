package com.georgv.audioworkstation.ui.screens.projects

/** Production session metadata for a normal (non-loop) overdub recording — cleared on transport stop. */
data class ActiveNormalOverdubContext(
    val backingTrackId: String,
    val backingWavPath: String,
    val backingTimelineStartOffsetMs: Long,
    val playbackStartMs: Long,
)
