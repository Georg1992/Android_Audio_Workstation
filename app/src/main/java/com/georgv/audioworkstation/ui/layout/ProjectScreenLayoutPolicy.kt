package com.georgv.audioworkstation.ui.layout

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.theme.Dimens

/**
 * Track list sizing derived from viewport height so roughly an integer count of rows fits in one
 * screenful (reduces clipped bottom rows during drag/reorder).
 *
 * On **phone-sized** screens (shortest Configuration side `< 600dp`), the policy prefers **four**
 * slots per page. Row height **compresses** to fit four whole cards whenever the viewport can
 * satisfy [phoneMinimumSlotDpForFourRows]. Only when four rows cannot meet that compressed floor does
 * it fall back to **three** rows (still partitioned to the full viewport height).
 *
 * On **tablet-sized** shortest sides, feasibility uses [minComfortTrackSlotHeightDp] so rows stay at
 * the historical comfort density; slot count picks the largest *n* that fits under that constraint.
 */
data class ProjectTrackLayoutSpec(
    val layoutEnvironment: LayoutEnvironment,
    val targetVisibleTrackSlots: Int,
    val trackSlotHeight: Dp,
    val listVerticalSpacing: Dp,
)

private fun slotHeightForSlots(
    viewportHeight: Dp,
    gap: Dp,
    slots: Int,
): Dp = (viewportHeight - gap * (slots - 1)) / slots

/**
 * Comfortable minimum driven by themed faders/waveform (used for tablets / feasibility scan).
 */
private fun minComfortTrackSlotHeightDp(): Dp {
    val verticalPadding = Dimens.TileInnerPadding * 2
    val titleRow = Dimens.MenuButtonSize
    val spacer = Dimens.PanelPadding
    val waveform = Dimens.PlaceholderHeight
    val leftColumn = verticalPadding + titleRow + spacer + waveform
    return maxOf(leftColumn, Dimens.FaderMinHeight + verticalPadding).coerceAtLeast(96.dp)
}

private fun listVerticalSpacingFor(environment: LayoutEnvironment): Dp =
    when (environment.heightClass) {
        HeightClass.Compact -> Dimens.Gap
        HeightClass.Medium -> Dimens.Gap
        HeightClass.Expanded ->
            Dimens.Gap +
                when (environment.widthClass) {
                    WidthClass.Compact -> 0.dp
                    WidthClass.Medium -> 2.dp
                    WidthClass.Expanded -> 4.dp
                }
    }

private const val MaxVisibleSlots = 8
private const val MinVisibleSlots = 3

/** Floor for “4 full rows on a phone” — below this height per row would hide core controls; only then fallback to 3. */
private val phoneMinimumSlotDpForFourRows = 64.dp

fun projectTrackLayoutSpec(
    environment: LayoutEnvironment,
    trackListViewportHeight: Dp,
    trackListViewportWidth: Dp,
): ProjectTrackLayoutSpec {
    val gap = listVerticalSpacingFor(environment)
    val minComfort = minComfortTrackSlotHeightDp()
    if (trackListViewportHeight <= 0.dp) {
        return ProjectTrackLayoutSpec(
            layoutEnvironment = environment,
            targetVisibleTrackSlots = MinVisibleSlots,
            trackSlotHeight = minComfort,
            listVerticalSpacing = gap,
        )
    }
    val narrowList = trackListViewportWidth < 360.dp
    val effectiveMaxSlots =
        if (narrowList) {
            6
        } else {
            MaxVisibleSlots
        }
    val shortestSideDp =
        kotlin.math.min(environment.availableWidth.value, environment.availableHeight.value).dp
    val phoneSized = shortestSideDp < 600.dp

    if (phoneSized) {
        val slot4 = slotHeightForSlots(trackListViewportHeight, gap, 4)
        return if (slot4 >= phoneMinimumSlotDpForFourRows) {
            ProjectTrackLayoutSpec(
                layoutEnvironment = environment,
                targetVisibleTrackSlots = 4,
                trackSlotHeight = slot4,
                listVerticalSpacing = gap,
            )
        } else {
            val slots = 3
            val slot3 = slotHeightForSlots(trackListViewportHeight, gap, slots)
            ProjectTrackLayoutSpec(
                layoutEnvironment = environment,
                targetVisibleTrackSlots = slots,
                trackSlotHeight = slot3.coerceAtLeast(1.dp),
                listVerticalSpacing = gap,
            )
        }
    }

    var maxFeasibleN = MinVisibleSlots
    var slotForChosen = slotHeightForSlots(trackListViewportHeight, gap, MinVisibleSlots)
    for (n in effectiveMaxSlots downTo MinVisibleSlots) {
        val slot = slotHeightForSlots(trackListViewportHeight, gap, n)
        if (slot >= minComfort) {
            maxFeasibleN = n
            slotForChosen = slot
            break
        }
    }
    return ProjectTrackLayoutSpec(
        layoutEnvironment = environment,
        targetVisibleTrackSlots = maxFeasibleN,
        trackSlotHeight = slotForChosen,
        listVerticalSpacing = gap,
    )
}
