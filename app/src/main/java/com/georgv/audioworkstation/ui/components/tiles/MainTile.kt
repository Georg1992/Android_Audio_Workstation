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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
            val narrow = maxWidth < 170.dp

            val iconSize = if (narrow) Dimens.IconTileSize * 1.15f else Dimens.IconTileSize

            @Composable
            fun IconBox() {
                Box(
                    modifier = Modifier
                        .size(iconSize)
                        .clip(smallShape)
                        .background(accent)
                        .border(Dimens.Stroke, AppColors.Line, smallShape),
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
            fun AccentBar() {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.AccentBarHeight)
                        .clip(smallShape)
                        .background(accent)
                        .border(Dimens.Stroke, AppColors.Line, smallShape)
                )
            }

            if (narrow) {
                // --- Narrow: content centered both horizontally and vertically ---
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
                            IconBox()

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

                    AccentBar()
                }

            } else {
                // --- Normal: icon left, text right, accent bar bottom ---
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconBox()
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

                    AccentBar()
                }
            }
        }
    }
}




