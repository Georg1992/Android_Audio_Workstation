package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.theme.AppColors

@Composable
fun TransportPanel(
    isRecording: Boolean,
    isPlaying: Boolean,
    isPlayEnabled: Boolean,
    isStopEnabled: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(AppColors.Bg)
            .border(1.dp, AppColors.Line, shape)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {

        TransportButton(
            color = AppColors.Green,
            enabled = isPlayEnabled,
            onClick = onPlay,
            isActive = isPlaying
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = AppColors.Line)
        }

        TransportButton(
            color = AppColors.Yellow,
            enabled = isStopEnabled,
            onClick = onStop,
            isActive = false
        ) {
            Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = AppColors.Line)
        }

        TransportButton(
            color = AppColors.Red,
            enabled = true,
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
    val shape = RoundedCornerShape(8.dp)

    val bgColor = when {
        !enabled -> AppColors.Bg
        isActive -> color
        else -> AppColors.Bg
    }

    val borderColor = when {
        isActive -> AppColors.Line
        else -> AppColors.Line
    }

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape),
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


