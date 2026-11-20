package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.core.audio.TransportWaveformTrace
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppOpacity

/**
 * One line for the transport screen.
 * At rest it lies flat. While samples arrive it follows them.
 * Subclass and override [Line] to change the stroke.
 */
open class TransportWaveform {
    @Composable
    open fun Line(samples: FloatArray, modifier: Modifier = Modifier) {
        check(samples.size == TransportWaveformTrace.PointCount)
        Canvas(modifier = modifier) {
            val inset = LineInset.toPx()
            val mid = size.height / 2f
            val reach = size.height * LineReach
            val span = (size.width - inset * 2f).coerceAtLeast(0f)
            val last = samples.lastIndex
            val path = Path()
            var peak = 0f
            for (index in samples.indices) {
                val sample = samples[index].coerceIn(-1f, 1f)
                val magnitude = if (sample < 0f) -sample else sample
                if (magnitude > peak) peak = magnitude
                val x = inset + span * index / last
                val y = mid - sample * reach
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (peak > Movement) {
                drawPath(
                    path = path,
                    color = AppColors.SurfaceRaised.copy(alpha = AppOpacity.disabled),
                    style = Stroke(
                        width = GlowWidth.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
            drawPath(
                path = path,
                color = AppColors.SurfaceRaised,
                style = Stroke(
                    width = Hairline.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }

    private companion object {
        val LineInset = 8.dp
        val GlowWidth = 6.dp
        val Hairline = 2.dp
        const val LineReach = 0.42f
        const val Movement = 0.02f
    }
}
