package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.PanRange
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Typical hardware pot / DAW pan-knob arc: ~270° with center at the top. */
private const val PanKnobStartDegrees = 135f
private const val PanKnobSweepDegrees = 270f
private const val FullCircleDegrees = 360f
/** A full -1..+1 sweep takes roughly this many knob widths of drag distance. */
private const val PanKnobWidthsForFullSpan = 5f
private val PanKnobValueLineHeight = 7.sp

private fun panKnobCenterRelativeDegrees(): Float = PanKnobSweepDegrees * 0.5f

private fun panKnobEndDegrees(): Float = (PanKnobStartDegrees + PanKnobSweepDegrees) % FullCircleDegrees

private fun degreesToRad(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()

private fun panToRelativeDegrees(pan: Float): Float =
    (PanRange.clamp(pan) + 1f) * 0.5f * PanKnobSweepDegrees

private fun panToIndicatorAngleRad(pan: Float): Float {
    val degrees = PanKnobStartDegrees + panToRelativeDegrees(pan)
    return degreesToRad(degrees)
}

/** Same center-to-label distance as the value readout above the knob. */
private fun panKnobLabelDistanceFromCenterPx(
    knobSizePx: Float,
    valueLineHeightPx: Float,
): Float = knobSizePx / 2f + valueLineHeightPx / 2f

@Composable
private fun BoxScope.PanSweepEndpointLabel(
    text: String,
    degrees: Float,
    labelDistanceFromCenterPx: Float,
    style: TextStyle,
) {
    val angleRad = degreesToRad(degrees)
    Text(
        text = text,
        style = style,
        lineHeight = PanKnobValueLineHeight,
        maxLines = 1,
        modifier =
            Modifier
                .align(Alignment.Center)
                .offset {
                    IntOffset(
                        x = (cos(angleRad) * labelDistanceFromCenterPx).roundToInt(),
                        y = (sin(angleRad) * labelDistanceFromCenterPx).roundToInt(),
                    )
                },
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
    var dragPan by remember { mutableStateOf<Float?>(null) }
    val continuousPan = dragPan ?: PanRange.clamp(pan)
    val displayPan = PanRange.snapToIndicatorStep(continuousPan)
    val currentPan by rememberUpdatedState(PanRange.clamp(pan))

    val label = PanRange.label(displayPan)
    val valueText = PanRange.formatValue(displayPan)
    val knobSize = Dimens.TrackHeaderButtonSize
    val density = LocalDensity.current
    val knobSizePx = with(density) { knobSize.toPx() }
    val valueLineHeightPx = with(density) { PanKnobValueLineHeight.toPx() }
    val labelDistanceFromCenterPx =
        panKnobLabelDistanceFromCenterPx(
            knobSizePx = knobSizePx,
            valueLineHeightPx = valueLineHeightPx,
        )
    val contentDescription = stringResource(R.string.cd_track_pan, label, valueText)
    val valueLabelStyle =
        TextStyle(
            color = AppColors.Line,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
    val panGestureEnabled = enabled && onPanChange != null
    val panPerPixel = if (knobSizePx > 0f) 2f / (knobSizePx * PanKnobWidthsForFullSpan) else 0f
    val latestOnPanChange by rememberUpdatedState(onPanChange)
    val latestOnPanCommit by rememberUpdatedState(onPanCommit)

    Column(
        modifier =
            modifier
                .width(knobSize)
                .semantics { this.contentDescription = contentDescription }
                .then(
                    if (panGestureEnabled) {
                        Modifier.pointerInput(Unit) {
                            var lastEmittedSnap = PanRange.snapToIndicatorStep(currentPan)
                            val commitDrag: () -> Unit = {
                                latestOnPanCommit?.invoke(
                                    PanRange.snapToIndicatorStep(dragPan ?: currentPan),
                                )
                                dragPan = null
                            }
                            detectDragGestures(
                                onDragStart = {
                                    dragPan = currentPan
                                    lastEmittedSnap = PanRange.snapToIndicatorStep(currentPan)
                                },
                                onDragEnd = commitDrag,
                                onDragCancel = commitDrag,
                            ) { change, dragAmount ->
                                change.consume()
                                val knobDelta = dragAmount.x - dragAmount.y
                                val nextPan =
                                    PanRange.clamp(
                                        (dragPan ?: currentPan) + knobDelta * panPerPixel,
                                    )
                                dragPan = nextPan
                                val snapped = PanRange.snapToIndicatorStep(nextPan)
                                if (snapped != lastEmittedSnap) {
                                    lastEmittedSnap = snapped
                                    latestOnPanChange?.invoke(snapped)
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = valueText,
            style = valueLabelStyle,
            lineHeight = PanKnobValueLineHeight,
            maxLines = 1,
        )
        Box(modifier = Modifier.size(knobSize)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = Dimens.Stroke.toPx().coerceAtLeast(1f)
                val radius = size.minDimension / 2f - stroke - 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    color = AppColors.Bg,
                    radius = radius,
                    center = center,
                )
                val ringStroke = Stroke(width = stroke, cap = StrokeCap.Round)
                drawCircle(
                    color = AppColors.Line,
                    radius = radius,
                    center = center,
                    style = ringStroke,
                )
                val tickCount = 11
                val tickInner = radius - 0.5f
                val tickOuter = radius + 3f
                for (index in 0 until tickCount) {
                    val tickAngle =
                        degreesToRad(
                            PanKnobStartDegrees + (index.toFloat() / (tickCount - 1)) * PanKnobSweepDegrees,
                        )
                    drawLine(
                        color = AppColors.Line.copy(alpha = 0.55f),
                        start =
                            center + Offset(cos(tickAngle) * tickInner, sin(tickAngle) * tickInner),
                        end =
                            center + Offset(cos(tickAngle) * tickOuter, sin(tickAngle) * tickOuter),
                        strokeWidth = 1f,
                        cap = StrokeCap.Round,
                    )
                }
                val centerRelativeDeg = panKnobCenterRelativeDegrees()
                val indicatorRelativeDeg = panToRelativeDegrees(displayPan)
                val panArcSweepDeg = abs(indicatorRelativeDeg - centerRelativeDeg)
                if (panArcSweepDeg > 0.05f) {
                    val arcStartDeg =
                        PanKnobStartDegrees + minOf(centerRelativeDeg, indicatorRelativeDeg)
                    val panArcBounds =
                        Offset(center.x - radius, center.y - radius) to Size(radius * 2f, radius * 2f)
                    val panArcHaloStroke = Stroke(width = stroke + 2f, cap = StrokeCap.Round)
                    val panArcStroke = Stroke(width = stroke + 1f, cap = StrokeCap.Round)
                    drawArc(
                        color = AppColors.Line,
                        startAngle = arcStartDeg,
                        sweepAngle = panArcSweepDeg,
                        useCenter = false,
                        topLeft = panArcBounds.first,
                        size = panArcBounds.second,
                        style = panArcHaloStroke,
                    )
                    drawArc(
                        color = AppColors.Cyan,
                        startAngle = arcStartDeg,
                        sweepAngle = panArcSweepDeg,
                        useCenter = false,
                        topLeft = panArcBounds.first,
                        size = panArcBounds.second,
                        style = panArcStroke,
                    )
                }
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
            PanSweepEndpointLabel(
                text = "L",
                degrees = PanKnobStartDegrees,
                labelDistanceFromCenterPx = labelDistanceFromCenterPx,
                style = valueLabelStyle,
            )
            PanSweepEndpointLabel(
                text = "R",
                degrees = panKnobEndDegrees(),
                labelDistanceFromCenterPx = labelDistanceFromCenterPx,
                style = valueLabelStyle,
            )
        }
    }
}
