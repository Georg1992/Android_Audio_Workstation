package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlin.math.roundToInt

@Composable
fun TrackCard(
    title: String,
    isSelected: Boolean,
    isRecording: Boolean,
    gain: Float,
    onGainChange: (Float) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(Dimens.TileRadius)

    val bg = when {
        isRecording -> AppColors.Red
        isSelected -> AppColors.Green
        else -> AppColors.Bg
    }

    var menuExpanded by remember { mutableStateOf(false) }

    val menuShape = RoundedCornerShape(Dimens.TileRadius)
    val buttonShape = RoundedCornerShape(Dimens.MediumRadius)
    val itemShape = RoundedCornerShape(Dimens.MediumRadius)

    val gainClamped = gain.coerceIn(0f, 100f)
    val gainText = gainClamped.roundToInt().toString()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(bg)
            .border(Dimens.Stroke, AppColors.Line, cardShape)
            .clickable {
                if (menuExpanded) menuExpanded = false else onClick()
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(Dimens.TileInnerPadding),
            verticalAlignment = Alignment.Top
        ) {
            // LEFT
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = AppColors.Line,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Box(
                        modifier = Modifier
                            .size(Dimens.MenuButtonSize)
                            .clip(buttonShape)
                            .border(Dimens.Stroke, AppColors.Line, buttonShape)
                            .clickable { menuExpanded = !menuExpanded },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Track menu",
                            tint = AppColors.Line
                        )
                    }
                }

                if (menuExpanded) {
                    Spacer(Modifier.height(Dimens.PanelPadding))

                    Column(
                        modifier = Modifier
                            .align(Alignment.End)
                            .wrapContentWidth()
                            .clip(menuShape)
                            .background(AppColors.Bg)
                            .border(Dimens.Stroke, AppColors.Line, menuShape)
                            .padding(Dimens.Stroke),
                        verticalArrangement = Arrangement.spacedBy(Dimens.Stroke)
                    ) {
                        MenuRowRight(
                            text = "Delete",
                            icon = {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = AppColors.Line
                                )
                            },
                            shape = itemShape
                        ) {
                            menuExpanded = false
                            onDelete()
                        }

                        MenuRowRight(
                            text = "Placeholder",
                            icon = {
                                Icon(
                                    imageVector = Icons.Filled.BrokenImage,
                                    contentDescription = null,
                                    tint = AppColors.Line
                                )
                            },
                            shape = itemShape
                        ) {
                            menuExpanded = false
                        }
                    }
                }

                Spacer(Modifier.height(Dimens.PanelPadding))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.PlaceholderHeight)
                        .clip(RoundedCornerShape(Dimens.MediumRadius))
                        .background(AppColors.Bg)
                        .border(Dimens.Stroke, AppColors.Line, RoundedCornerShape(Dimens.MediumRadius))
                )
            }

            Spacer(Modifier.width(Dimens.Gap))

            // RIGHT: fader + number use as much vertical space as possible; number on bottom
            Column(
                modifier = Modifier
                    .width(Dimens.FaderWidth)
                    .heightIn(min = Dimens.FaderMinHeight)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Fader(
                    value = gainClamped,
                    onValueChange = { onGainChange(it.coerceIn(0f, 100f)) },
                    valueRange = 0f..100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                )
                Text(
                    text = gainText,
                    color = AppColors.Line,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun MenuRowRight(
    text: String,
    icon: @Composable () -> Unit,
    shape: RoundedCornerShape,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .wrapContentWidth()
            .heightIn(min = Dimens.MenuRowMinHeight)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.Gap, vertical = Dimens.SmallRadius),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        icon()
        Spacer(Modifier.width(Dimens.PanelPadding))
        Text(
            text = text,
            color = AppColors.Line,
            maxLines = 1
        )
    }
}


