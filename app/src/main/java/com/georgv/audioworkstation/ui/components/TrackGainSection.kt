package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.georgv.audioworkstation.core.audio.GainRange
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlin.math.roundToInt

@Composable
fun TrackGainSection(
    gain: Float,
    onGainChange: ((Float) -> Unit)?,
    onGainCommit: ((Float) -> Unit)?,
    enabled: Boolean,
    fillTrackHeight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Local UI state: the thumb and label follow the finger immediately
    // and are only re-synced from the DB when `gain` changes externally.
    var displayGain by remember(gain) {
        mutableFloatStateOf(gain.coerceIn(GainRange.Min, GainRange.Max))
    }
    val gainText = displayGain.roundToInt().toString()

    if (fillTrackHeight) {
        Column(
            modifier = modifier
                .width(Dimens.FaderWidth)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Fader(
                value = displayGain,
                onValueChange = { value ->
                    val clamped = value.coerceIn(GainRange.Min, GainRange.Max)
                    displayGain = clamped
                    onGainChange?.invoke(clamped)
                },
                onValueChangeFinished = {
                    onGainCommit?.invoke(displayGain)
                },
                valueRange = GainRange.Range,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true)
                    .fillMaxHeight(),
                bottomInsetPx = 0f,
            )
            Text(
                text = gainText,
                style =
                    TextStyle(
                        color = AppColors.Line,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                lineHeight = 10.sp,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        return
    }

    Column(
        modifier = modifier
            .width(Dimens.FaderWidth)
            .heightIn(min = Dimens.FaderMinHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Fader(
            value = displayGain,
            onValueChange = { value ->
                val clamped = value.coerceIn(GainRange.Min, GainRange.Max)
                displayGain = clamped
                onGainChange?.invoke(clamped)
            },
            onValueChangeFinished = {
                onGainCommit?.invoke(displayGain)
            },
            valueRange = GainRange.Range,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true),
        )
        Text(
            text = gainText,
            color = AppColors.Line,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = Dimens.TightGap),
        )
    }
}
