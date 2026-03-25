package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun TransportPanel(
    isRecording: Boolean,
    isPlaying: Boolean,
    isPlayEnabled: Boolean,
    isStopEnabled: Boolean,
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
            .padding(bottom = Dimens.PanelPadding)
            .clip(shape)
            .background(AppColors.Bg)
            .border(Dimens.Stroke, AppColors.Line, shape)
            .padding(vertical = Dimens.PanelPadding)
            .then(
                if (inputLocked) {
                    Modifier.pointerInput(Unit) {
                        while (true) {
                            awaitEachGesture {
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {

        TransportButton(
            color = AppColors.Green,
            enabled = isPlayEnabled && !inputLocked,
            onClick = onPlay,
            isActive = isPlaying
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = AppColors.Line)
        }

        TransportButton(
            color = AppColors.Yellow,
            enabled = isStopEnabled && !inputLocked,
            onClick = onStop,
            isActive = false
        ) {
            Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = AppColors.Line)
        }

        TransportButton(
            color = AppColors.Red,
            enabled = !inputLocked,
            onClick = onRecord,
            isActive = isRecording
        ) {
            Icon(Icons.Filled.FiberManualRecord, contentDescription = "Record", tint = AppColors.Line)
        }


    }
}

@Composable
fun TransportButton(
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
            enabled = enabled
        ) {
            Box(
                modifier = Modifier.alpha(if (enabled) 1f else 0.4f)
            ) {
                content()
            }
        }
    }
}


