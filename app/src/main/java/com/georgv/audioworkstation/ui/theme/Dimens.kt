package com.georgv.audioworkstation.ui.theme

import androidx.compose.ui.unit.dp

/** Fraction of the project screen width used for the transport panel container. */
const val TransportPanelWidthFraction = 0.7f

object Dimens {
    val Gap = 10.dp

    val TileRadius = 10.dp
    val SmallRadius = 6.dp
    val MediumRadius = 8.dp

    val Stroke = 1.dp

    val TileInnerPadding = 12.dp
    val IconTileSize = 36.dp

    val AccentBarHeight = 8.dp
    val PanelPadding = 8.dp
    /** Horizontal inset of track cards from the project list; inner row padding compensates so timeline stays aligned with the scrubber. */
    val TrackCardOuterPaddingHorizontal = PanelPadding

    // top bar (content row below status bar)
    val TopBarHeight = 30.dp
    val TopBarNavIconInset = 5.dp

    // language icon
    val LangChipSize = 28.dp
    val LangChipRadius = 6.dp

    // track card (layout policy also drives project list row height — see projectTrackLayoutSpec)
    val TrackHeaderButtonSize = 28.dp
    val TrackActionLedSize = 3.dp
    val TrackActionLedInset = 3.dp
    val TrackActionIconGlowBlur = 5.dp
    val PlaceholderHeight = 56.dp
    val FaderWidth = 40.dp
    val FaderMinHeight = 100.dp
    val MenuRowMinHeight = 32.dp

    // transport
    val TransportPanelRadius = 12.dp
    val TransportButtonSize = 48.dp
    val TransportIconSize = 24.dp

    // project screen (top placeholder strip)
    val PanelPlaceholderHeight = 36.dp

    // placeholder screens (Community, Library, Devices)
    val ScreenContentPadding = 16.dp

    // main tile narrow breakpoint
    val MainTileNarrowBreakpoint = 170.dp

    // glow effect (reusable "bulb" halo around icons/buttons)
    // spacing between adjacent icons so their glows don't cross into each other
    val IconGlowSpacing = 12.dp

    // tight separators (e.g. between fader and its numeric readout)
    val TightGap = 2.dp

    // drag overlay (track card lifted while reordering)
    val DragOverlayShadow = 12.dp

    // fader internals
    val FaderTrackWidth = 8.dp
    val FaderThumbWidth = 18.dp
    val FaderThumbHeight = 14.dp
    val FaderTickShortLen = 3.dp
    val FaderTickMidLen = 6.dp
    val FaderTickGap = 3.dp
}
