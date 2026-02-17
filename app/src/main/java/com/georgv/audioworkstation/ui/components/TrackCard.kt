package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.theme.AppColors

@Composable
fun TrackCard(
    title: String,
    isSelected: Boolean,
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)

    val bg = when {
        isRecording -> AppColors.Red
        isSelected -> AppColors.Green
        else -> AppColors.Bg
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, AppColors.Line, shape)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(
            text = title,
            color = AppColors.Line,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.Bg)
                .border(1.dp, AppColors.Line, RoundedCornerShape(8.dp))
        )
    }
}


