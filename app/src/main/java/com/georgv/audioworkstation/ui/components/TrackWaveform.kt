package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlinx.coroutines.delay

private fun densifyPeaks(peaks: List<Float>): List<Float> {
    if (peaks.isEmpty()) return peaks
    val dense = ArrayList<Float>(peaks.size * 2)
    for (index in peaks.indices) {
        val current = peaks[index]
        val next = peaks.getOrElse(index + 1) { current }
        dense += current
        dense += (current + next) * 0.5f
    }
    return dense
}

data class WaveformPeaks(val amplitudes: List<Float>) {
    companion object {
        val Placeholder = WaveformPeaks(
            densifyPeaks(
                listOf(
                    0.18f, 0.28f, 0.42f, 0.34f, 0.62f, 0.74f, 0.46f, 0.32f,
                    0.54f, 0.82f, 0.66f, 0.38f, 0.24f, 0.48f, 0.72f, 0.58f,
                    0.36f, 0.22f, 0.44f, 0.68f, 0.88f, 0.64f, 0.40f, 0.30f,
                    0.52f, 0.76f, 0.60f, 0.34f, 0.20f, 0.40f, 0.56f, 0.30f,
                )
            )
        )
    }
}

private const val RecordingWaveformBarCount = 72
private const val WaveformCenterAlpha = 0.18f
private const val RecordingWaveformFrameMs = 33L
private const val RecordingWaveformSilenceFloor = 0.008f
private const val RecordingWaveformQuietVisiblePeak = 0.16f

@Composable
fun TrackWaveform(
    modifier: Modifier = Modifier,
    peaks: WaveformPeaks = WaveformPeaks.Placeholder,
) {
    WaveformCanvas(
        modifier = modifier,
        peakCount = peaks.amplitudes.size,
        peakAt = { index -> peaks.amplitudes[index] },
        barAlphaAt = { 1f },
    )
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

@Composable
private fun WaveformCanvas(
    modifier: Modifier,
    redrawToken: Int = 0,
    peakCount: Int,
    peakAt: (Int) -> Float,
    barAlphaAt: (Int) -> Float,
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.PlaceholderHeight)
            .clip(shape)
            .background(AppColors.Bg)
            .border(Dimens.Stroke, AppColors.Line, shape)
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

        val horizontalInset = size.width * 0.04f
        val availableWidth = (size.width - horizontalInset * 2f).coerceAtLeast(0f)
        val barSlotWidth = availableWidth / peakCount
        val barWidth = (barSlotWidth * 0.46f).coerceAtLeast(1f)
        val maxHalfHeight = size.height * 0.42f

        for (index in 0 until peakCount) {
            val normalized = peakAt(index).coerceIn(0f, 1f)
            val barHeight = (maxHalfHeight * normalized).coerceAtLeast(1f)
            val left = horizontalInset + index * barSlotWidth + (barSlotWidth - barWidth) / 2f
            drawRoundRect(
                color = AppColors.Line.copy(alpha = barAlphaAt(index).coerceIn(0f, 1f)),
                topLeft = Offset(left, centerY - barHeight),
                size = Size(barWidth, barHeight * 2f),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
