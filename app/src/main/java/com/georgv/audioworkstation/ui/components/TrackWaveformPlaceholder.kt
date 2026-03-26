package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun TrackWaveformPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.PlaceholderHeight)
            .background(AppColors.Bg, RoundedCornerShape(Dimens.MediumRadius))
            .border(
                Dimens.Stroke,
                AppColors.Line,
                RoundedCornerShape(Dimens.MediumRadius)
            )
    )
}
