package com.georgv.audioworkstation.ui.theme

import androidx.compose.ui.graphics.Color

object AppColors {
    // pipette palette
    val Bg = Color(0xFFF2F2F2)
    val Line = Color(0xFF000000)

    val Green = Color(0xFF02FC57)
    val Yellow = Color(0xFFFDFF1F)
    val Cyan = Color(0xFF01FEFF)
    val Pink = Color(0xFFFFD2FF)
    val Red = Color(0xFFFF2E4A)

    val Text = Line
    /**
     * Highlight color used to mark "activated" affordances (toggled loop, open overflow menu,
     * selected sample rate). Points at the palette's [Yellow] so a future accent swap is a
     * one-line change here.
     */
    val Accent = Yellow

    // Fader (palette-colored moving parts)
    val FaderTrackAbove = Color(0xFFFFFFFF)
    val FaderTrackBelow = Color(0xFF000000)
    val FaderTrackBorder = Line
    val FaderTick = Cyan
    val FaderThumb = Bg
    val FaderThumbNotch = Color(0x66000000)
}

/** Reusable alpha tokens. Keep in lockstep with palette swaps. */
object Alphas {
    /** Disabled / dimmed surfaces (e.g. transport buttons that are not interactive). */
    const val Disabled = 0.4f
    /** Slightly muted icons (e.g. main tile icons that aren't the primary action). */
    const val MutedIcon = 0.65f
    /** Heavy shadow / spot color for elevated drag overlays. */
    const val OverlayShadow = 0.6f
    /** Reorder handle, idle. */
    const val HandleIdle = 0.35f
    /** Reorder handle, active. */
    const val HandleActive = 0.85f
}
