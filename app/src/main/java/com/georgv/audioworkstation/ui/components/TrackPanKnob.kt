package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.PanRange
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val PanKnobStartDegrees = 120f
private const val PanKnobSweepDegrees = 300f

private fun panKnobEndDegrees(): Float = (PanKnobStartDegrees + PanKnobSweepDegrees) % 360f

private fun degreesToRad(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()

private fun panToIndicatorAngleRad(pan: Float): Float {
    val normalized = (PanRange.clamp(pan) + 1f) * 0.5f
    val degrees = PanKnobStartDegrees + normalized * PanKnobSweepDegrees
    return degreesToRad(degrees)
}

private fun isOnPanKnobArc(degrees: Float): Boolean {
    val relative = (degrees - PanKnobStartDegrees + 360f) % 360f
    return relative <= PanKnobSweepDegrees
}

private fun angleRadToPan(angleRad: Float): Float {
    var degrees = Math.toDegrees(angleRad.toDouble()).toFloat()
    while (degrees < 0f) degrees += 360f
    while (degrees >= 360f) degrees -= 360f
    val clampedDegrees =
        if (isOnPanKnobArc(degrees)) {
            degrees
        } else {
            val distToStart = (PanKnobStartDegrees - degrees + 360f) % 360f
            val distToEnd = (degrees - panKnobEndDegrees() + 360f) % 360f
            if (distToStart <= distToEnd) PanKnobStartDegrees else panKnobEndDegrees()
        }
    val relative = (clampedDegrees - PanKnobStartDegrees + 360f) % 360f
    val normalized = relative / PanKnobSweepDegrees
    return PanRange.clamp(normalized * 2f - 1f)
}

private fun DrawScope.drawCenteredText(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    style: TextStyle,
    center: Offset,
) {
    val layout = textMeasurer.measure(text, style)
    drawText(
        textMeasurer = textMeasurer,
        text = text,
        style = style,
        topLeft =
            Offset(
                x = center.x - layout.size.width / 2f,
                y = center.y - layout.size.height / 2f,
            ),
    )
}

@Composable
fun TrackPanKnob(
    pan: Float,
    onPanChange: ((Float) -> Unit)?,
    onPanCommit: ((Float) -> Unit)?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var displayPan by remember(pan) { mutableFloatStateOf(PanRange.clamp(pan)) }
    val label = PanRange.label(displayPan)
    val valueText = PanRange.formatValue(displayPan)
    val knobSize = Dimens.TrackHeaderButtonSize
    val contentDescription = stringResource(R.string.cd_track_pan, label, valueText)
    val textMeasurer = rememberTextMeasurer()
    val endpointLabelStyle =
        TextStyle(
            color = AppColors.Line,
            fontSize = 6.sp,
            fontFamily = FontFamily.Monospace,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )

    Canvas(
        modifier =
            modifier
                .size(knobSize)
                .semantics { this.contentDescription = contentDescription }
                .then(
                    if (enabled && onPanChange != null) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures {
                                displayPan = PanRange.Center
                                onPanChange.invoke(PanRange.Center)
                                onPanCommit?.invoke(PanRange.Center)
                            }
                        }.pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { onPanCommit?.invoke(displayPan) },
                                onDragCancel = { onPanCommit?.invoke(displayPan) },
                            ) { change, _ ->
                                change.consume()
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val pointer = change.position - center
                                val nextPan = angleRadToPan(atan2(pointer.y, pointer.x))
                                displayPan = nextPan
                                onPanChange.invoke(nextPan)
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
    ) {
        val stroke = Dimens.Stroke.toPx().coerceAtLeast(1f)
        val radius = size.minDimension / 2f - stroke
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = AppColors.Bg,
            radius = radius,
            center = center,
        )
        drawCircle(
            color = AppColors.Line,
            radius = radius,
            center = center,
            style = Stroke(width = stroke),
        )
        val tickCount = 11
        for (index in 0 until tickCount) {
            val tickAngle =
                degreesToRad(
                    PanKnobStartDegrees + (index.toFloat() / (tickCount - 1)) * PanKnobSweepDegrees,
                )
            val inner = radius - 3f
            val outer = radius - 1f
            drawLine(
                color = AppColors.Line.copy(alpha = 0.55f),
                start =
                    center + Offset(cos(tickAngle) * inner, sin(tickAngle) * inner),
                end =
                    center + Offset(cos(tickAngle) * outer, sin(tickAngle) * outer),
                strokeWidth = 1f,
                cap = StrokeCap.Round,
            )
        }
        val labelRadius = radius * 0.62f
        val minusOneAngle = degreesToRad(PanKnobStartDegrees)
        val oneAngle = degreesToRad(panKnobEndDegrees())
        drawCenteredText(
            textMeasurer = textMeasurer,
            text = "-1",
            style = endpointLabelStyle,
            center =
                center +
                    Offset(
                        x = cos(minusOneAngle) * labelRadius,
                        y = sin(minusOneAngle) * labelRadius,
                    ),
        )
        drawCenteredText(
            textMeasurer = textMeasurer,
            text = "1",
            style = endpointLabelStyle,
            center =
                center +
                    Offset(
                        x = cos(oneAngle) * labelRadius,
                        y = sin(oneAngle) * labelRadius,
                    ),
        )
        val indicatorAngle = panToIndicatorAngleRad(displayPan)
        val indicatorEnd =
            center +
                Offset(
                    x = cos(indicatorAngle) * (radius - 4f),
                    y = sin(indicatorAngle) * (radius - 4f),
                )
        drawLine(
            color = AppColors.Line,
            start = center,
            end = indicatorEnd,
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = AppColors.Line,
            radius = 2f,
            center = center,
        )
    }
}
