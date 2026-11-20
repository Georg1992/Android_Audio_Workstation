package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

/**
 * Small transport display to the right of play and stop.
 * Subclass and override [Show] to change the messages or the picture.
 */
open class TransportMessageScreen {
    @Composable
    open fun Show(
        message: String?,
        visualizing: Boolean,
        positionMs: Long,
        modifier: Modifier = Modifier,
    ) {
        val shape = RoundedCornerShape(Dimens.SmallRadius)
        Box(
            modifier = modifier
                .width(ScreenWidth)
                .height(Dimens.TransportButtonSize)
                .clip(shape)
                .background(AppColors.Line, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (visualizing) {
                val fractal = remember { TransportFractalVisualizer() }
                fractal.Screen(
                    positionMs = positionMs,
                    live = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (message != null) {
                Text(
                    text = message,
                    style = AppText.TileSubtitle,
                    color = AppColors.SurfaceRaised,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Dimens.TightGap),
                )
            }
        }
    }

    private companion object {
        val ScreenWidth = 128.dp
    }
}
