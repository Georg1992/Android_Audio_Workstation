package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.theme.AppColors

/**
 * Minimal DAW-like vertical fader (Waves-ish), fully custom drawn.
 * Range default is 0..100 for casual UX.
 */
@Composable
fun Fader(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    enabled: Boolean = true,

    // Layout
    trackWidth: Dp = 10.dp,
    thumbWidth: Dp = 22.dp,
    thumbHeight: Dp = 14.dp,
    tickCount: Int = 13,

    shaftBg: Color = Color(0xFF0A0A0A),
    /** Fill from track top down to thumb center (higher value / “up”). */
    trackAboveThumb: Color = Color.White,
    /** Fill from thumb center down to track bottom (lower value / “down”). */
    trackBelowThumb: Color = Color.Black,
    trackBorder: Color = AppColors.FaderTrackBorder,
    tickColor: Color = AppColors.FaderTick,
    thumbColor: Color = AppColors.FaderThumb,
    thumbBorder: Color = AppColors.Line,
) {
    val density = LocalDensity.current
    val trackW = with(density) { trackWidth.toPx() }
    val thumbW = with(density) { thumbWidth.toPx() }
    val thumbH = with(density) { thumbHeight.toPx() }

    fun clamp(v: Float) = v.coerceIn(valueRange.start, valueRange.endInclusive)

    fun norm(v: Float): Float {
        val cl = clamp(v)
        val span = (valueRange.endInclusive - valueRange.start).takeIf { it != 0f } ?: 1f
        return (cl - valueRange.start) / span
    }

    fun denorm(t: Float): Float {
        val span = (valueRange.endInclusive - valueRange.start).takeIf { it != 0f } ?: 1f
        return valueRange.start + (t.coerceIn(0f, 1f) * span)
    }

    var lastHeight by remember { mutableStateOf(0f) }
    val topPad = thumbH / 2f
    val bottomPadPx = 2f

    fun trackYBounds(canvasHeight: Float): Pair<Float, Float> {
        val h = canvasHeight.coerceAtLeast(0f)
        val topY = topPad.coerceIn(0f, h)
        val bottomY = (h - bottomPadPx).coerceIn(0f, h)
        val low = minOf(topY, bottomY)
        val high = maxOf(topY, bottomY)
        return low to high
    }

    Box(
        modifier = modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { pos ->
                        val h = lastHeight
                        if (h <= 0f) return@detectDragGestures

                        val (trackTop, trackBottom) = trackYBounds(h)
                        val usable = (trackBottom - trackTop).coerceAtLeast(1f)
                        val y = pos.y.coerceIn(trackTop, trackBottom)
                        val t = 1f - ((y - trackTop) / usable)
                        onValueChange(clamp(denorm(t)))
                    },
                    onDrag = { change, _ ->
                        val h = lastHeight
                        if (h <= 0f) return@detectDragGestures

                        val (trackTop, trackBottom) = trackYBounds(h)
                        val usable = (trackBottom - trackTop).coerceAtLeast(1f)
                        val y = change.position.y.coerceIn(trackTop, trackBottom)
                        val t = 1f - ((y - trackTop) / usable)
                        onValueChange(clamp(denorm(t)))
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            lastHeight = size.height

            val cx = size.width / 2f
            val (trackTop, trackBottom) = trackYBounds(size.height)
            val trackH = (trackBottom - trackTop).coerceAtLeast(0f)
            val fullH = trackH.coerceAtLeast(1f)

            val trackLeft = cx - (trackW / 2f)
            val trackRight = cx + (trackW / 2f)

            val valueT = norm(value) // 0..1
            val thumbCenterY = trackBottom - (valueT * fullH)

            val trackClip = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = trackLeft,
                        top = trackTop,
                        right = trackRight,
                        bottom = trackBottom,
                        topLeftCornerRadius = CornerRadius(3f, 3f),
                        topRightCornerRadius = CornerRadius(3f, 3f),
                        bottomRightCornerRadius = CornerRadius(3f, 3f),
                        bottomLeftCornerRadius = CornerRadius(3f, 3f),
                    )
                )
            }
            val splitY = thumbCenterY.coerceIn(trackTop, trackBottom)
            clipPath(trackClip) {
                val topH = splitY - trackTop
                if (topH > 0f) {
                    drawRect(
                        color = trackAboveThumb,
                        topLeft = Offset(trackLeft, trackTop),
                        size = Size(trackW, topH)
                    )
                }
                val botH = trackBottom - splitY
                if (botH > 0f) {
                    drawRect(
                        color = trackBelowThumb,
                        topLeft = Offset(trackLeft, splitY),
                        size = Size(trackW, botH)
                    )
                }
            }
            drawRoundRect(
                color = trackBorder,
                topLeft = Offset(trackLeft, trackTop),
                size = Size(trackW, trackH),
                cornerRadius = CornerRadius(3f, 3f),
                style = Stroke(width = 1f)
            )

            // Ticks on sides
            val ticks = tickCount.coerceAtLeast(2)
            for (i in 0 until ticks) {
                val t = i.toFloat() / (ticks - 1).toFloat()
                val y = trackBottom - (t * fullH)

                val isMajor = (i == 0 || i == ticks - 1 || i == (ticks - 1) / 2)
                val len = if (isMajor) 10f else 6f
                val gap = 8f
                val alpha = if (isMajor) 0.9f else 0.65f

                drawLine(
                    color = tickColor.copy(alpha = alpha),
                    start = Offset(trackLeft - gap - len, y),
                    end = Offset(trackLeft - gap, y),
                    strokeWidth = 1f
                )
                drawLine(
                    color = tickColor.copy(alpha = alpha),
                    start = Offset(trackRight + gap, y),
                    end = Offset(trackRight + gap + len, y),
                    strokeWidth = 1f
                )
            }

            // Thumb position (thumbCenterY already computed)
            val thumbLeft = cx - (thumbW / 2f)
            val thumbTopMax = (size.height - thumbH).coerceAtLeast(0f)
            val thumbTop = (thumbCenterY - thumbH / 2f).coerceIn(0f, thumbTopMax)

            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(thumbLeft, thumbTop),
                size = androidx.compose.ui.geometry.Size(thumbW, thumbH),
                cornerRadius = CornerRadius(3f, 3f)
            )
            drawRoundRect(
                color = thumbBorder,
                topLeft = Offset(thumbLeft, thumbTop),
                size = androidx.compose.ui.geometry.Size(thumbW, thumbH),
                cornerRadius = CornerRadius(3f, 3f),
                style = Stroke(width = 1f)
            )

            // Thumb notch
            drawLine(
                color = AppColors.FaderThumbNotch,
                start = Offset(thumbLeft + 4f, thumbCenterY),
                end = Offset(thumbLeft + thumbW - 4f, thumbCenterY),
                strokeWidth = 1f
            )
        }
    }
}