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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.theme.AppColors

@Composable
fun TransportPanel(
    isRecording: Boolean,
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
            onClick = onPlay
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = AppColors.Line)
        }

        TransportButton(
            color = AppColors.Yellow,
            onClick = onStop
        ) {
            Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = AppColors.Line)
        }

        TransportButton(
            color = AppColors.Red,
            onClick = onRecord
        ) {
            Icon(Icons.Filled.FiberManualRecord, contentDescription = "Record", tint = AppColors.Line)
        }
    }
}

@Composable
private fun TransportButton(
    color: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .background(color)
            .border(1.dp, AppColors.Line, shape),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) {
            content()
        }
    }
}


