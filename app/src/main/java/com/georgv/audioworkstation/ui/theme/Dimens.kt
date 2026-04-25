package com.georgv.audioworkstation.ui.theme

import androidx.compose.ui.unit.dp

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

    // top bar
    val TopBarHeight = 30.dp

    // language icon
    val LangChipSize = 28.dp
    val LangChipRadius = 6.dp

    // track card
    val MenuButtonSize = 40.dp
    val PlaceholderHeight = 42.dp
    val FaderWidth = 44.dp
    val FaderMinHeight = 100.dp
    val MenuRowMinHeight = 32.dp

    // transport
    val TransportPanelRadius = 14.dp
    val TransportButtonSize = 56.dp

    // project screen (top placeholder strip)
    val PanelPlaceholderHeight = 36.dp

    // placeholder screens (Community, Library, Devices)
    val ScreenContentPadding = 16.dp

    // main tile narrow breakpoint
    val MainTileNarrowBreakpoint = 170.dp

    // drag handle (triangle in track card)
    val DragHandleSize = 24.dp

    // glow effect (reusable "bulb" halo around icons/buttons)
    val GlowBlur = 10.dp
    // spacing between adjacent icons so their glows don't cross into each other
    val IconGlowSpacing = 12.dp

    // tight separators (e.g. between fader and its numeric readout)
    val TightGap = 2.dp

    // drag overlay (track card lifted while reordering)
    val DragOverlayShadow = 24.dp
    val DragOverlayBorder = 2.dp

    // fader internals
    val FaderTrackWidth = 10.dp
    val FaderThumbWidth = 22.dp
    val FaderThumbHeight = 14.dp
    val FaderTickShortLen = 3.dp
    val FaderTickMidLen = 6.dp
    val FaderTickGap = 3.dp
}
