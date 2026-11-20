package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    playheadPositionMs: Long,
    timelineDurationMs: Long,
    screenMessage: String? = null,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit,
    isRecordEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    inputLocked: Boolean = false,
    timeline: (@Composable () -> Unit)? = null,
) {
    val playheadTimeLabel = formatTimelineDuration(playheadPositionMs)
    TransportCluster(
        clock = TransportClock(playheadTimeLabel, playheadTimeLabel),
        modifier = modifier,
        inputLocked = inputLocked,
        timeline = { timeline?.invoke() },
        buttons = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(
                paint = TransportButtonPaint(AppColors.Green, AppColors.SurfacePressed),
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
                paint = TransportButtonPaint(AppColors.SurfacePressed, AppColors.SurfacePressed),
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

            val messageScreen = remember { TransportMessageScreen() }
            messageScreen.Show(
                message = screenMessage,
                visualizing = isPlaying || isRecording,
                positionMs = playheadPositionMs,
                durationMs = timelineDurationMs,
                modifier = Modifier.weight(1f),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
            ) {
                TransportButton(
                    paint = TransportButtonPaint(AppColors.Red, AppColors.SurfacePressed),
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
            }
        }
        },
    )
}

internal data class TransportClock(
    val label: String,
    val description: String,
)

@Composable
internal fun TransportCluster(
    clock: TransportClock,
    timeline: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    inputLocked: Boolean = false,
    buttons: @Composable ColumnScope.() -> Unit,
) {
    val well = RoundedCornerShape(Dimens.SmallRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .transportChrome()
            .padding(horizontal = Dimens.Gap, vertical = Dimens.TransportPanelPadding)
            .consumeAllPointers(enabled = inputLocked),
        verticalArrangement = Arrangement.spacedBy(Dimens.Gap / 2),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap / 2),
            content = buttons,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.TransportTimelineHeight)
                .clip(well)
                .background(AppColors.Bg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                timeline()
            }
            TransportPlayheadTimeDisplay(
                clock = clock,
                modifier = Modifier.alpha(if (inputLocked) AppOpacity.disabled else 1f),
            )
        }
    }
}

internal fun Modifier.transportControlChrome(
    shape: Shape,
    fill: Color,
): Modifier =
    shadow(elevation = Dimens.ControlElevation, shape = shape)
        .background(fill)

internal fun Modifier.transportChrome(): Modifier {
    val shape = RoundedCornerShape(Dimens.TransportPanelRadius)
    return shadow(
        elevation = Dimens.PanelElevation,
        shape = shape,
        clip = false,
    ).background(AppColors.SurfaceRaised, shape)
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
internal fun TransportPlayheadTimeDisplay(
    clock: TransportClock,
    modifier: Modifier = Modifier,
) {
    Text(
        text = clock.label,
        color = AppColors.Line,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier
            .padding(horizontal = Dimens.Gap)
            .semantics { contentDescription = clock.description },
    )
}

internal data class TransportButtonPaint(
    val active: Color,
    val resting: Color,
)

@Composable
internal fun TransportButton(
    paint: TransportButtonPaint,
    enabled: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)
    val bgColor = if (enabled && isActive) paint.active else paint.resting

    Box(
        modifier = Modifier
            .size(Dimens.TransportButtonSize)
            .transportControlChrome(shape = shape, fill = bgColor),
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
