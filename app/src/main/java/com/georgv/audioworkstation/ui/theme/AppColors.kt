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

    // Accent palette (PascalCase brand tokens)
    val Green = Color(0xFF02FC57)
    val Yellow = Color(0xFFFDFF1F)
    val Cyan = Color(0xFF00FFF6)
    val Pink = Color(0xFFFFD2FF)
    val Red = Color(0xFFFF2E4A)

    /** Loop region overlay tint on the timeline waveform. */
    val LoopRegionFill = Green.copy(alpha = AppOpacity.loopRegionFill)
    /** Opaque loop tint for header buttons — matches [LoopRegionFill] on [SurfacePanel]. */
    val LoopButtonActiveBackground = lerp(SurfacePanel, Green, AppOpacity.loopRegionFill)

    // Semantic aliases
    val Text = Line
    val Accent = Yellow

    /** [Line] at [AppOpacity.muted] — tile subtitles, library secondary text. */
    val iconMuted = Line.copy(alpha = AppOpacity.muted)
    /** [Line] at [AppOpacity.subtle] — track metadata and helper labels. */
    val textSecondary = Line.copy(alpha = AppOpacity.subtle)
    /** [Line] at [AppOpacity.emphasis] — transport and scrubber readouts. */
    val labelEmphasis = Line.copy(alpha = AppOpacity.emphasis)

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
