package com.georgv.audioworkstation.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a soft "bulb" glow behind the composable, extending [blurRadius] outside its bounds.
 *
 * Place BEFORE any `.clip(...)` in the modifier chain so the glow can extend past the
 * composable's own shape. The glow is implemented as concentric rounded rectangles with
 * decreasing alpha — portable across all API levels and inexpensive to render.
 *
 * @param color Glow color (full alpha; the modifier handles the falloff).
 * @param blurRadius How far the glow reaches outside the composable.
 * @param cornerRadius Corner radius of the composable whose edge the glow follows.
 * @param intensity Peak alpha at the inner edge of the glow (0..1).
 * @param layers Number of concentric rings used to approximate the blur. Higher = smoother.
 */
fun Modifier.glow(
    color: Color,
    blurRadius: Dp,
    cornerRadius: Dp = 0.dp,
    intensity: Float = 0.9f,
    layers: Int = 10
): Modifier = drawBehind {
    val blurPx = blurRadius.toPx()
    val cornerPx = cornerRadius.toPx()
    for (i in layers downTo 1) {
        val progress = i.toFloat() / layers
        val falloff = (1f - progress) * (1f - progress)
        val alpha = (falloff * intensity).coerceIn(0f, 1f)
        val outset = blurPx * progress
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(-outset, -outset),
            size = Size(size.width + 2 * outset, size.height + 2 * outset),
            cornerRadius = CornerRadius(cornerPx + outset)
        )
    }
}
