package com.georgv.audioworkstation.ui.theme

import androidx.compose.ui.graphics.Color

object AppColors {

    /** App shell background; splash still uses `@color/app_window_bg` until activity starts — keep that hex aligned when changing [Bg]. */
    val Bg = Color(0xFFF3EFE4)

    val SurfacePanel = Color(0xFFF7F4EE)
    val SurfaceRaised = Color(0xFFFFFFFF)
    val SurfacePressed = Color(0xFFE4E0D8)

    // Core lines/text
    val Line = Color(0xFF111111)

    // Accent palette
    val Green = Color(0xFF02FC57)
    val Yellow = Color(0xFFFDFF1F)
    val Cyan = Color(0xFF00FFF6)
    val Pink = Color(0xFFFFD2FF)
    val Red = Color(0xFFFF2E4A)

    // Semantic aliases
    val Text = Line
    val Accent = Yellow

    // Utility neutrals
    val WhiteSoft = Color(0xFFFFFCF7)
    val BlackSoftTransparent = Color(0x66000000)

    // Fader
    val FaderTrackAbove = WhiteSoft
    val FaderTrackBelow = Line
    val FaderTrackBorder = Line
    val FaderTick = Line
    val FaderThumb = SurfaceRaised
    val FaderThumbNotch = BlackSoftTransparent
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
