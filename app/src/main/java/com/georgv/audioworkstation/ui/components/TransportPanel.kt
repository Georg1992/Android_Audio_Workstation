package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.modifiers.consumeAllPointers
import com.georgv.audioworkstation.ui.theme.Alphas
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun TransportPanel(
    isRecording: Boolean,
    isPlaying: Boolean,
    isPlayEnabled: Boolean,
    isStopEnabled: Boolean,
    playheadTimeLabel: String,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
    inputLocked: Boolean = false
) {
    val shape = RoundedCornerShape(Dimens.TransportPanelRadius)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.SurfacePanel)
            .border(Dimens.Stroke, AppColors.Line, shape)
            .padding(horizontal = Dimens.Gap, vertical = 6.dp)
            .consumeAllPointers(enabled = inputLocked),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(
                color = AppColors.Green,
                enabled = isPlayEnabled && !inputLocked,
                onClick = onPlay,
                isActive = isPlaying,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play),
                    tint = AppColors.Line,
                    modifier = Modifier.size(Dimens.TransportIconSize),
                )
            }

            TransportButton(
                color = AppColors.Yellow,
                enabled = isStopEnabled && !inputLocked,
                onClick = onStop,
                isActive = false,
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.cd_stop),
                    tint = AppColors.Line,
                    modifier = Modifier.size(Dimens.TransportIconSize),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            TransportButton(
                color = AppColors.Red,
                enabled = !inputLocked,
                onClick = onRecord,
                isActive = isRecording,
            ) {
                Icon(
                    Icons.Filled.FiberManualRecord,
                    contentDescription = stringResource(R.string.cd_record),
                    tint = AppColors.Line,
                    modifier = Modifier.size(Dimens.TransportIconSize),
                )
            }
            TransportPlayheadTimeDisplay(
                label = playheadTimeLabel,
                modifier = Modifier.alpha(if (inputLocked) Alphas.Disabled else 1f),
            )
        }
    }
}

@Composable
private fun TransportPlayheadTimeDisplay(
    label: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)

    Box(
        modifier = modifier
            .height(Dimens.TransportButtonSize)
            .widthIn(min = 36.dp)
            .clip(shape)
            .background(AppColors.Bg)
            .border(Dimens.Stroke, AppColors.Line, shape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = AppColors.Line.copy(alpha = 0.88f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun TransportButton(
    color: Color,
    enabled: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)

    val bgColor = when {
        !enabled -> AppColors.Bg
        isActive -> color
        else -> AppColors.Bg
    }

    Box(
        modifier = Modifier
            .size(Dimens.TransportButtonSize)
            .clip(shape)
            .background(bgColor)
            .border(Dimens.Stroke, AppColors.Line, shape),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(Dimens.TransportButtonSize),
        ) {
            Box(
                modifier = Modifier.alpha(if (enabled) 1f else Alphas.Disabled)
            ) {
                content()
            }
        }
    }
}
