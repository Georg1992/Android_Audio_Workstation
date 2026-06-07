package com.georgv.audioworkstation.ui.theme

/** Shared opacity multipliers — camelCase semantic names, reused across UI. */
object AppOpacity {
    /** Disabled / non-interactive controls (transport, buttons). */
    const val disabled = 0.4f
    /** Muted icons and secondary copy on light surfaces. */
    const val muted = 0.65f
    /** De-emphasized labels (metadata, helper text). */
    const val subtle = 0.58f
    /** Strong secondary labels (transport readouts, scrubber time). */
    const val emphasis = 0.88f
    /** Loop region waveform overlay fill. */
    const val loopRegionFill = 0.38f
}
