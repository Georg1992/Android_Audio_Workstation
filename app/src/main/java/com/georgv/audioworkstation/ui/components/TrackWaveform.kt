package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.georgv.audioworkstation.core.audio.waveform.WaveformPeaks
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlinx.coroutines.delay

private const val RecordingWaveformBarCount = 72
private const val WaveformCenterAlpha = 0.18f
private const val WaveformOutsideLoopAlpha = 0.32f
private const val RecordingWaveformFrameMs = 33L
private const val RecordingWaveformSilenceFloor = 0.008f
private const val RecordingWaveformQuietVisiblePeak = 0.16f

@Composable
fun TrackWaveform(
    modifier: Modifier = Modifier,
    peaks: WaveformPeaks = WaveformPeaks.Placeholder,
    horizontalInsetFraction: Float = 0.04f,
    loopRegionStartFraction: Float? = null,
    loopRegionEndFraction: Float? = null,
) {
    if (peaks.isStereo) {
        val left = peaks.leftAmplitudes.orEmpty()
        val right = peaks.rightAmplitudes.orEmpty()
        Column(modifier = modifier.fillMaxSize()) {
            WaveformCanvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .fillMaxHeight(),
                peakCount = left.size,
                peakAt = { index -> left[index] },
                barAlphaAt = { 1f },
                horizontalInsetFraction = horizontalInsetFraction,
                minCanvasHeight = 0.dp,
                loopRegionStartFraction = loopRegionStartFraction,
                loopRegionEndFraction = loopRegionEndFraction,
            )
            WaveformCanvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .fillMaxHeight(),
                peakCount = right.size,
                peakAt = { index -> right[index] },
                barAlphaAt = { 1f },
                horizontalInsetFraction = horizontalInsetFraction,
                minCanvasHeight = 0.dp,
                loopRegionStartFraction = loopRegionStartFraction,
                loopRegionEndFraction = loopRegionEndFraction,
            )
        }
    } else {
        WaveformCanvas(
            modifier = modifier,
            peakCount = peaks.amplitudes.size,
            peakAt = { index -> peaks.amplitudes[index] },
            barAlphaAt = { 1f },
            horizontalInsetFraction = horizontalInsetFraction,
            loopRegionStartFraction = loopRegionStartFraction,
            loopRegionEndFraction = loopRegionEndFraction,
        )
    }
}

@Composable
fun RecordingWaveform(
    modifier: Modifier = Modifier,
    inputLevel: Float = 0f,
) {
    val latestInputLevel by rememberUpdatedState(inputLevel.coerceIn(0f, 1f))
    val rollingPeaks = remember {
        FloatArray(RecordingWaveformBarCount) { 0f }
    }
    var rollingRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            val normalized = latestInputLevel
            for (index in 0 until rollingPeaks.lastIndex) {
                rollingPeaks[index] = rollingPeaks[index + 1]
            }
            rollingPeaks[rollingPeaks.lastIndex] =
                if (normalized <= RecordingWaveformSilenceFloor) {
                    0f
                } else {
                    normalized.coerceAtLeast(RecordingWaveformQuietVisiblePeak)
                }
            rollingRevision++
            delay(RecordingWaveformFrameMs)
        }
    }

    val revision = rollingRevision
    val recentBarStart = RecordingWaveformBarCount - 5
    WaveformCanvas(
        modifier = modifier,
        redrawToken = revision,
        peakCount = RecordingWaveformBarCount,
        peakAt = { index -> rollingPeaks[index] },
        barAlphaAt = { index ->
            if (index > recentBarStart) 1f else 0.68f
        },
    )
}

private fun waveformBarInLoopRegion(
    barIndex: Int,
    peakCount: Int,
    loopRegionStartFraction: Float?,
    loopRegionEndFraction: Float?,
): Boolean {
    if (loopRegionStartFraction == null || loopRegionEndFraction == null || peakCount <= 0) {
        return true
    }
    val barCenterFraction = (barIndex + 0.5f) / peakCount.toFloat()
    return barCenterFraction in loopRegionStartFraction..loopRegionEndFraction
}

@Composable
private fun WaveformCanvas(
    modifier: Modifier,
    redrawToken: Int = 0,
    peakCount: Int,
    peakAt: (Int) -> Float,
    barAlphaAt: (Int) -> Float,
    horizontalInsetFraction: Float = 0.04f,
    minCanvasHeight: Dp = Dimens.PlaceholderHeight,
    loopRegionStartFraction: Float? = null,
    loopRegionEndFraction: Float? = null,
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)
    val sizedModifier =
        modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .then(
                if (minCanvasHeight > 0.dp) {
                    Modifier.heightIn(min = minCanvasHeight)
                } else {
                    Modifier
                },
            )
    Canvas(
        modifier = sizedModifier
            .clip(shape)
            .background(AppColors.Bg),
    ) {
        redrawToken
        val centerY = size.height / 2f
        drawLine(
            color = AppColors.Line.copy(alpha = WaveformCenterAlpha),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1f,
        )

        if (peakCount <= 0) return@Canvas

        val horizontalInset = size.width * horizontalInsetFraction.coerceIn(0f, 0.2f)
        val availableWidth = (size.width - horizontalInset * 2f).coerceAtLeast(0f)
        val barSlotWidth = availableWidth / peakCount
        val barWidth = (barSlotWidth * 0.46f).coerceAtLeast(1f)
        val maxHalfHeight = size.height * 0.42f

        for (index in 0 until peakCount) {
            val normalized = peakAt(index).coerceIn(0f, 1f)
            val barHeight = (maxHalfHeight * normalized).coerceAtLeast(1f)
            val left = horizontalInset + index * barSlotWidth + (barSlotWidth - barWidth) / 2f
            val inLoopRegion =
                waveformBarInLoopRegion(
                    barIndex = index,
                    peakCount = peakCount,
                    loopRegionStartFraction = loopRegionStartFraction,
                    loopRegionEndFraction = loopRegionEndFraction,
                )
            val barAlpha =
                if (inLoopRegion) {
                    barAlphaAt(index).coerceIn(0f, 1f)
                } else {
                    WaveformOutsideLoopAlpha
                }
            drawRoundRect(
                color = AppColors.Line.copy(alpha = barAlpha),
                topLeft = Offset(left, centerY - barHeight),
                size = Size(barWidth, barHeight * 2f),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
fun ImportingWaveform(
    @Suppress("UNUSED_PARAMETER") progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        ImportWaveformSkeleton(modifier = Modifier.fillMaxSize())
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = AppColors.Bg.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun ImportWaveformSkeleton(modifier: Modifier = Modifier) {
    val skeletonPeaks =
        remember {
            List(36) { index ->
                ((index % 5) + 3) / 12f
            }
        }
    TrackWaveform(
        peaks = WaveformPeaks(amplitudes = skeletonPeaks),
        horizontalInsetFraction = 0f,
        modifier = modifier,
    )
}
