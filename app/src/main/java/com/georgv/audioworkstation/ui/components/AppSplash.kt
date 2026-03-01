package com.georgv.audioworkstation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun AppSplash(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(AppColors.Bg),
            contentAlignment = Alignment.Center
        ) {
            // low-fi “card”
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
                modifier = Modifier
                    .padding(24.dp)
                    .border(Dimens.Stroke, AppColors.Line, RoundedCornerShape(Dimens.TransportPanelRadius))
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 88.dp, height = 22.dp)
                        .border(Dimens.Stroke, AppColors.Line, RoundedCornerShape(Dimens.SmallRadius))
                )

                Text(
                    text = "AudioWorkstation",
                    color = AppColors.Line,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "booting…",
                    color = AppColors.Line.copy(alpha = 0.65f),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
