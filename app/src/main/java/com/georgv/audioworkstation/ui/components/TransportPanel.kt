package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.georgv.audioworkstation.core.audio.MasterPeakIndicatorLevel
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.modifiers.consumeAllPointers
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppOpacity
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun TransportPanel(
    isRecording: Boolean,
    isPlaying: Boolean,
    isPlayEnabled: Boolean,
    isStopEnabled: Boolean,
    stopButtonShowsPause: Boolean,
    playheadTimeLabel: String,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit,
    isRecordEnabled: Boolean = true,
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
                if (stopButtonShowsPause) {
                    Icon(
                        Icons.Filled.Pause,
                        contentDescription = stringResource(R.string.cd_pause),
                        tint = AppColors.Line,
                        modifier = Modifier.size(Dimens.TransportIconSize),
                    )
                } else {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.cd_stop),
                        tint = AppColors.Line,
                        modifier = Modifier.size(Dimens.TransportIconSize),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            TransportButton(
                color = AppColors.Red,
                enabled = isRecordEnabled && !inputLocked,
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
                modifier = Modifier.alpha(if (inputLocked) AppOpacity.disabled else 1f),
            )
        }
    }
}

@Composable
fun MasterOutputPeakIndicator(
    peakDbText: String,
    indicatorLevel: MasterPeakIndicatorLevel,
    modifier: Modifier = Modifier,
) {
    val lampColor =
        when (indicatorLevel) {
            MasterPeakIndicatorLevel.Red -> AppColors.Red
            MasterPeakIndicatorLevel.Yellow -> AppColors.Yellow
            MasterPeakIndicatorLevel.Green -> AppColors.Green
            MasterPeakIndicatorLevel.Inactive -> AppColors.Green.copy(alpha = 0.35f)
        }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.TightGap),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(lampColor),
        )
        Text(
            text = peakDbText,
            style = AppText.TopBarTitle.copy(fontSize = 8.sp),
            color = AppColors.Text,
            maxLines = 1,
        )
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
            color = AppColors.labelEmphasis,
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
                modifier = Modifier.alpha(if (enabled) 1f else AppOpacity.disabled)
            ) {
                content()
            }
        }
    }
}
