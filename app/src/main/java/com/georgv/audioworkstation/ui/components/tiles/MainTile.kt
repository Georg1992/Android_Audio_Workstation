package com.georgv.audioworkstation.ui.components.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun MainTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(Dimens.TileRadius)
    val smallShape = RoundedCornerShape(Dimens.SmallRadius)

    Surface(
        onClick = onClick,
        shape = shape,
        color = AppColors.Bg,
        shadowElevation = 0.dp,
        modifier = modifier
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .border(Dimens.Stroke, AppColors.Line, shape)
                .padding(Dimens.TileInnerPadding)
        ) {
            val narrow = maxWidth < Dimens.MainTileNarrowBreakpoint
            val iconSize = if (narrow) Dimens.IconTileSize * 1.15f else Dimens.IconTileSize

            if (narrow) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            MainTileIconBox(
                                icon = icon,
                                accent = accent,
                                iconSize = iconSize,
                                cornerShape = smallShape
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = title,
                                    style = AppText.TileTitle,
                                    color = AppColors.Line,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = subtitle,
                                    style = AppText.TileSubtitle,
                                    color = AppColors.Line.copy(alpha = 0.65f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    MainTileAccentBar(accent = accent, cornerShape = smallShape)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MainTileIconBox(
                            icon = icon,
                            accent = accent,
                            iconSize = iconSize,
                            cornerShape = smallShape
                        )
                        Spacer(Modifier.width(Dimens.Gap))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = AppText.TileTitle,
                                color = AppColors.Line,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = subtitle,
                                style = AppText.TileSubtitle,
                                color = AppColors.Line.copy(alpha = 0.65f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    MainTileAccentBar(accent = accent, cornerShape = smallShape)
                }
            }
        }
    }
}

@Composable
private fun MainTileIconBox(
    icon: ImageVector,
    accent: Color,
    iconSize: Dp,
    cornerShape: RoundedCornerShape
) {
    Box(
        modifier = Modifier
            .size(iconSize)
            .clip(cornerShape)
            .background(accent)
            .border(Dimens.Stroke, AppColors.Line, cornerShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppColors.Line
        )
    }
}

@Composable
private fun MainTileAccentBar(
    accent: Color,
    cornerShape: RoundedCornerShape
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.AccentBarHeight)
            .clip(cornerShape)
            .background(accent)
            .border(Dimens.Stroke, AppColors.Line, cornerShape)
    )
}
