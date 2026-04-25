package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.georgv.audioworkstation.core.audio.GainRange
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

/** Corner radius (px) for the track and thumb. Visual constant, no UX significance. */
private const val FaderRoundedCornerPx = 3f
/** Stroke width for the track border, thumb border, ticks and notch. */
private const val FaderStrokePx = 1f
/** Bottom padding (px) so the bottom tick doesn't kiss the canvas edge. */
private const val FaderBottomPadPx = 2f
/** Inset (px) of the thumb's center notch from the thumb's left/right edge. */
private const val FaderThumbNotchInsetPx = 4f
/** Alpha for the off-center tick marks (the center 50% mark is full alpha). */
private const val FaderOffCenterTickAlpha = 0.8f

/**
 * Minimal DAW-like vertical fader (Waves-ish), fully custom drawn.
 * Range default is the gain percent range so the casual UX stays consistent across the app.
 */
@Composable
fun Fader(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = GainRange.Range,
    enabled: Boolean = true,
    trackWidth: Dp = Dimens.FaderTrackWidth,
    thumbWidth: Dp = Dimens.FaderThumbWidth,
    thumbHeight: Dp = Dimens.FaderThumbHeight,
    tickCount: Int = 13,
    trackAboveThumb: Color = AppColors.FaderTrackAbove,
    trackBelowThumb: Color = AppColors.FaderTrackBelow,
    trackBorder: Color = AppColors.FaderTrackBorder,
    tickColor: Color = AppColors.FaderTick,
    thumbColor: Color = AppColors.FaderThumb,
    thumbBorder: Color = AppColors.Line,
) {
    val density = LocalDensity.current
    val trackW = with(density) { trackWidth.toPx() }
    val thumbW = with(density) { thumbWidth.toPx() }
    val thumbH = with(density) { thumbHeight.toPx() }
    val tickShortLenPx = with(density) { Dimens.FaderTickShortLen.toPx() }
    val tickMidLenPx = with(density) { Dimens.FaderTickMidLen.toPx() }
    val tickGapPx = with(density) { Dimens.FaderTickGap.toPx() }
    val topPad = thumbH / 2f

    var lastHeightPx by remember { mutableFloatStateOf(0f) }
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

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

    fun trackYBounds(canvasHeight: Float): Pair<Float, Float> {
        val h = canvasHeight.coerceAtLeast(0f)
        val topY = topPad.coerceIn(0f, h)
        val bottomY = (h - FaderBottomPadPx).coerceIn(0f, h)
        val low = minOf(topY, bottomY)
        val high = maxOf(topY, bottomY)
        return low to high
    }

    // The thumb is rendered from localValue so it tracks the finger directly,
    // independent of recompositions driven by external state. External changes
    // are pulled into localValue only when the user isn't actively dragging.
    var isDragging by remember { mutableStateOf(false) }
    var localValue by remember { mutableFloatStateOf(clamp(value)) }
    LaunchedEffect(value) {
        if (!isDragging) localValue = clamp(value)
    }

    fun yToValue(y: Float, canvasHeight: Float): Float {
        val (trackTop, trackBottom) = trackYBounds(canvasHeight)
        val usable = (trackBottom - trackTop).coerceAtLeast(1f)
        val clampedY = y.coerceIn(trackTop, trackBottom)
        val t = 1f - ((clampedY - trackTop) / usable)
        return clamp(denorm(t))
    }

    Box(
        modifier = modifier
            .onSizeChanged { lastHeightPx = it.height.toFloat() }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    val h = lastHeightPx
                    if (h <= 0f) return@awaitEachGesture

                    // Consume the down so parent scrollables (LazyColumn) don't win the slop race.
                    down.consume()
                    isDragging = true
                    val initialValue = yToValue(down.position.y, h)
                    localValue = initialValue
                    latestOnValueChange(initialValue)

                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChange() != Offset.Zero) {
                                val v = yToValue(change.position.y, lastHeightPx.coerceAtLeast(1f))
                                localValue = v
                                latestOnValueChange(v)
                                change.consume()
                            }
                        }
                    } finally {
                        isDragging = false
                        latestOnValueChangeFinished?.invoke()
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val (trackTop, trackBottom) = trackYBounds(size.height)
            val trackH = (trackBottom - trackTop).coerceAtLeast(0f)
            val fullH = trackH.coerceAtLeast(1f)

            val trackLeft = cx - (trackW / 2f)
            val trackRight = cx + (trackW / 2f)

            val valueT = norm(localValue)
            val thumbCenterY = trackBottom - (valueT * fullH)

            val trackCornerRadius = CornerRadius(FaderRoundedCornerPx, FaderRoundedCornerPx)
            val trackClip = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = trackLeft,
                        top = trackTop,
                        right = trackRight,
                        bottom = trackBottom,
                        topLeftCornerRadius = trackCornerRadius,
                        topRightCornerRadius = trackCornerRadius,
                        bottomRightCornerRadius = trackCornerRadius,
                        bottomLeftCornerRadius = trackCornerRadius,
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
                cornerRadius = trackCornerRadius,
                style = Stroke(width = FaderStrokePx)
            )

            val ticks = tickCount.coerceAtLeast(2)
            val midIndex = (ticks - 1) / 2
            for (i in 0 until ticks) {
                val tickT = i.toFloat() / (ticks - 1).toFloat()
                val y = trackBottom - (tickT * fullH)

                val isCenter = (i == midIndex)
                val len = if (isCenter) tickMidLenPx else tickShortLenPx
                val alpha = if (isCenter) 1f else FaderOffCenterTickAlpha

                drawLine(
                    color = tickColor.copy(alpha = alpha),
                    start = Offset(trackLeft - tickGapPx - len, y),
                    end = Offset(trackLeft - tickGapPx, y),
                    strokeWidth = FaderStrokePx
                )
                drawLine(
                    color = tickColor.copy(alpha = alpha),
                    start = Offset(trackRight + tickGapPx, y),
                    end = Offset(trackRight + tickGapPx + len, y),
                    strokeWidth = FaderStrokePx
                )
            }

            val thumbLeft = cx - (thumbW / 2f)
            val thumbTopMax = (size.height - thumbH).coerceAtLeast(0f)
            val thumbTop = (thumbCenterY - thumbH / 2f).coerceIn(0f, thumbTopMax)

            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(thumbLeft, thumbTop),
                size = Size(thumbW, thumbH),
                cornerRadius = trackCornerRadius
            )
            drawRoundRect(
                color = thumbBorder,
                topLeft = Offset(thumbLeft, thumbTop),
                size = Size(thumbW, thumbH),
                cornerRadius = trackCornerRadius,
                style = Stroke(width = FaderStrokePx)
            )

            drawLine(
                color = AppColors.FaderThumbNotch,
                start = Offset(thumbLeft + FaderThumbNotchInsetPx, thumbCenterY),
                end = Offset(thumbLeft + thumbW - FaderThumbNotchInsetPx, thumbCenterY),
                strokeWidth = FaderStrokePx
            )
        }
    }
}
