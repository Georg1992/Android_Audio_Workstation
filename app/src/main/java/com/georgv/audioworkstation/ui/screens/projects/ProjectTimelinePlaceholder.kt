package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.georgv.audioworkstation.ui.modifiers.consumeAllPointers
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun ProjectTimelinePlaceholder(
    reorderActive: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(Dimens.TileRadius)

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding)
            .height(Dimens.PanelPlaceholderHeight)
            .background(AppColors.Bg, shape)
            .border(Dimens.Stroke, AppColors.Line, shape)
            .consumeAllPointers(enabled = reorderActive)
    )
}
