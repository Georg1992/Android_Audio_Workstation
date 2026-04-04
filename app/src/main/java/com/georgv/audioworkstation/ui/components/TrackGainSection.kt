package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlin.math.roundToInt

@Composable
fun TrackGainSection(
    gain: Float,
    onGainChange: ((Float) -> Unit)?,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val gainClamped = gain.coerceIn(0f, 100f)
    val gainText = gainClamped.roundToInt().toString()

    Column(
        modifier = modifier
            .width(Dimens.FaderWidth)
            .heightIn(min = Dimens.FaderMinHeight)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Fader(
            value = gainClamped,
            onValueChange = { value -> onGainChange?.invoke(value.coerceIn(0f, 100f)) },
            valueRange = 0f..100f,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
        )
        Text(
            text = gainText,
            color = AppColors.Line,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 2.dp)
        )
    }
}
