package com.georgv.audioworkstation.ui.components.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

    Surface(
        onClick = onClick,
        shape = shape,
        color = AppColors.Bg,
        shadowElevation = 0.dp,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .border(Dimens.Stroke, AppColors.Line, shape)
                .padding(Dimens.TileInnerPadding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {

                Row(verticalAlignment = Alignment.CenterVertically) {

                    Box(
                        modifier = Modifier
                            .size(Dimens.IconTileSize)
                            .clip(RoundedCornerShape(Dimens.SmallRadius))
                            .background(accent)
                            .border(
                                Dimens.Stroke,
                                AppColors.Line,
                                RoundedCornerShape(Dimens.SmallRadius)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = AppColors.Line
                        )
                    }

                    Spacer(Modifier.width(Dimens.Gap))

                    Column {
                        Text(
                            text = title,
                            style = AppText.TileTitle,
                            color = AppColors.Line
                        )
                        Text(
                            text = subtitle,
                            style = AppText.TileSubtitle,
                            color = AppColors.Line.copy(alpha = 0.65f)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.AccentBarHeight)
                        .clip(RoundedCornerShape(Dimens.SmallRadius))
                        .background(accent)
                        .border(
                            Dimens.Stroke,
                            AppColors.Line,
                            RoundedCornerShape(Dimens.SmallRadius)
                        )
                )
            }
        }
    }
}

