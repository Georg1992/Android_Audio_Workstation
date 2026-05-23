package com.georgv.audioworkstation.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Documents horizontal waveform chrome for scrubber vs track list without Compose.
 * Values mirror [Dimens.TileInnerPadding], [Dimens.Gap], [Dimens.FaderWidth], and
 * [TimelineWaveformWidthFraction] at a fixed parent width (412dp @ density 3, w412dp).
 */
class TimelineWaveformChromeLayoutTest {

    @Test
    fun `before fix duplicate list padding shifts and narrows lane waveform`() {
        val scrubber = scrubberWaveformBounds(parentWidthPx = PARENT_WIDTH_PX, extraListPaddingPx = 0f)
        val laneBeforeFix =
            trackWaveformBounds(
                parentWidthPx = PARENT_WIDTH_PX,
                extraListPaddingPx = TILE_INNER_PADDING_PX,
            )

        assertNotEquals(scrubber.globalXPx, laneBeforeFix.globalXPx, 0.5f)
        assertNotEquals(scrubber.widthPx, laneBeforeFix.widthPx, 0.5f)
        assertEquals(TILE_INNER_PADDING_PX, laneBeforeFix.globalXPx - scrubber.globalXPx, 0.5f)
    }

    @Test
    fun `after fix track waveform bounds match scrubber`() {
        val scrubber = scrubberWaveformBounds(parentWidthPx = PARENT_WIDTH_PX, extraListPaddingPx = 0f)
        val lane =
            trackWaveformBounds(
                parentWidthPx = PARENT_WIDTH_PX,
                extraListPaddingPx = 0f,
            )

        assertEquals(scrubber.globalXPx, lane.globalXPx, 0.5f)
        assertEquals(scrubber.widthPx, lane.widthPx, 0.5f)
    }

    private data class WaveformBounds(val globalXPx: Float, val widthPx: Float)

    private fun scrubberWaveformBounds(
        parentWidthPx: Float,
        extraListPaddingPx: Float,
    ): WaveformBounds {
        val horizontalPaddingPx = TILE_INNER_PADDING_PX + extraListPaddingPx
        val rowWidthPx = parentWidthPx - 2f * horizontalPaddingPx - GAP_PX - FADER_WIDTH_PX
        return WaveformBounds(
            globalXPx = horizontalPaddingPx,
            widthPx = rowWidthPx * TimelineWaveformWidthFraction,
        )
    }

    private fun trackWaveformBounds(
        parentWidthPx: Float,
        extraListPaddingPx: Float,
    ): WaveformBounds {
        val horizontalPaddingPx = TILE_INNER_PADDING_PX + extraListPaddingPx
        val rowWidthPx = parentWidthPx - 2f * horizontalPaddingPx - GAP_PX - FADER_WIDTH_PX
        return WaveformBounds(
            globalXPx = horizontalPaddingPx,
            widthPx = rowWidthPx * TimelineWaveformWidthFraction,
        )
    }

    private companion object {
        private const val DENSITY = 3f
        private const val PARENT_WIDTH_DP = 412f
        private const val PARENT_WIDTH_PX = PARENT_WIDTH_DP * DENSITY
        private const val TILE_INNER_PADDING_PX = 12f * DENSITY
        private const val GAP_PX = 10f * DENSITY
        private const val FADER_WIDTH_PX = 40f * DENSITY
    }
}
