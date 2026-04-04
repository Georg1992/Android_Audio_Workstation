package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.georgv.audioworkstation.ui.modifiers.consumeAllPointers
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun TopToolbarPanel(
    inputLocked: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit = {}
) {
    val shape = RoundedCornerShape(Dimens.TileRadius)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding)
            .height(Dimens.PanelPlaceholderHeight)
            .background(AppColors.Bg, shape)
            .border(Dimens.Stroke, AppColors.Line, shape)
            .consumeAllPointers(enabled = inputLocked)
            .padding(horizontal = Dimens.TileInnerPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
