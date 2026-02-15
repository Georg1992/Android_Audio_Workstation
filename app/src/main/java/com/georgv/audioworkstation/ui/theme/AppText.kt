package com.georgv.audioworkstation.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

object AppText {
    val TileTitle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp
    )

    val TileSubtitle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    )

    val TopBarTitle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp
    )

    // Если захочешь центрирование как дефолт:
    val TileTitleCenter = TileTitle.copy(textAlign = TextAlign.Center)
    val TileSubtitleCenter = TileSubtitle.copy(textAlign = TextAlign.Center)
}
