package com.georgv.audioworkstation.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

object AppColors {

    /** Page shell. Splash still uses `@color/app_window_bg` until activity starts — keep that hex aligned when changing [Bg]. */
    val Bg = Color(0xFFF3EFE4)

    /** Inset wells on a raised surface, such as a timeline lane. */
    val SurfacePanel = Color(0xFFF7F4EE)
    /** Cards, panels, the header, and dialogs. */
    val SurfaceRaised = Color(0xFFFFFFFF)
    /** Resting controls and text fields. */
    val SurfacePressed = Color(0xFFE4E0D8)

    /** Text and small marks such as ruler ticks. */
    val Line = Color(0xFF111111)

    /** Play, selection, and the primary action. */
    val Green = Color(0xFF02FC57)
    /** Warning and the accent. */
    val Yellow = Color(0xFFFDFF1F)
    /** Import in progress and level. */
    val Cyan = Color(0xFF00FFF6)
    /** Community accent. */
    val Pink = Color(0xFFFFD2FF)
    /** Record, error, and the playhead. */
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
    val BlackSoftTransparent = Color(0x66000000)

    // Fader
    val FaderTrackAbove = SurfacePressed
    val FaderTrackBelow = Cyan
    val FaderTick = Line
    val FaderThumb = SurfaceRaised
    val FaderThumbNotch = BlackSoftTransparent
}
