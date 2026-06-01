package com.georgv.audioworkstation.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

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
    /** Loop region overlay tint on the timeline waveform. */
    val LoopRegionFill = Green.copy(alpha = Alphas.LoopRegionFill)
    /** Opaque loop tint for header buttons — matches [LoopRegionFill] on [SurfacePanel]. */
    val LoopButtonActiveBackground = lerp(SurfacePanel, Green, Alphas.LoopRegionFill)
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
    val FaderTrackBelow = Cyan
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
    /** Track header action button — pressed scale target. */
    const val TrackActionPressedScale = 0.95f
    /** Idle chrome border. */
    const val TrackActionBorderIdle = 0.22f
    /** Active chrome border. */
    const val TrackActionBorderActive = 0.52f
    /** Pressed chrome border. */
    const val TrackActionBorderPressed = 0.38f
    /** Idle icon tint. */
    const val TrackActionIconIdle = 0.38f
    /** Active icon inner glow peak. */
    const val TrackActionIconGlow = 0.42f
    /** Loop region overlay fill alpha. */
    const val LoopRegionFill = 0.38f
}
