package com.georgv.audioworkstation.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wide Julia-set screen shown beside the transport time.
 * Subclass and override [render] to change the picture without editing the transport.
 */
open class TransportFractalVisualizer {
    @Composable
    fun Screen(
        positionMs: Long,
        live: Boolean,
        modifier: Modifier = Modifier,
    ) {
        val shape = RoundedCornerShape(Dimens.SmallRadius)
        var size by remember { mutableStateOf(IntSize.Zero) }
        val phase = if (live) positionMs / PhaseStepMs else RestPhase
        Box(
            modifier = modifier
                .clip(shape)
                .background(AppColors.Line, shape)
                .onSizeChanged { size = it },
        ) {
            val width = size.width
            val height = size.height
            val image = remember(width, height, phase) {
                if (width > 0 && height > 0) render(width, height, phase) else null
            }
            val picture = image
            if (picture != null) {
                Image(
                    bitmap = picture,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }

    open fun render(widthPx: Int, heightPx: Int, phase: Long): ImageBitmap {
        val renderWidth = widthPx.coerceAtMost(MaxRenderWidth).coerceAtLeast(1)
        val renderHeight = heightPx.coerceAtMost(MaxRenderHeight).coerceAtLeast(1)
        val pixels = IntArray(renderWidth * renderHeight)
        val turns = phase.toDouble() * PhaseTurns
        val cr = JuliaReal + JuliaSwing * cos(turns)
        val ci = JuliaImag + JuliaSwing * sin(turns)
        var y = 0
        while (y < renderHeight) {
            val zy0 = (y.toDouble() / renderHeight.toDouble() - Half) * PlaneHeight
            var x = 0
            val row = y * renderWidth
            while (x < renderWidth) {
                val zx0 = (x.toDouble() / renderWidth.toDouble() - Half) * PlaneWidth
                pixels[row + x] = escapeColor(zx0, zy0, cr, ci)
                x += 1
            }
            y += 1
        }
        val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, renderWidth, 0, 0, renderWidth, renderHeight)
        return bitmap.asImageBitmap()
    }

    private fun escapeColor(zx0: Double, zy0: Double, cr: Double, ci: Double): Int {
        var zx = zx0
        var zy = zy0
        var iteration = 0
        while (iteration < MaxIterations) {
            val zx2 = zx * zx
            val zy2 = zy * zy
            if (zx2 + zy2 > EscapeRadius2) {
                return paletteColor(iteration)
            }
            val nextX = zx2 - zy2 + cr
            zy = Twice * zx * zy + ci
            zx = nextX
            iteration += 1
        }
        return interior
    }

    private fun paletteColor(iteration: Int): Int {
        val color = when (iteration % PaletteSize) {
            0 -> AppColors.Green
            1 -> AppColors.Yellow
            CyanBand -> AppColors.Cyan
            else -> AppColors.Red
        }
        return color.toArgb()
    }

    private val interior = AppColors.Line.toArgb()

    private companion object {
        const val MaxIterations = 32
        const val MaxRenderWidth = 180
        const val MaxRenderHeight = 64
        const val EscapeRadius2 = 4.0
        const val PlaneWidth = 3.0
        const val PlaneHeight = 2.2
        const val Half = 0.5
        const val Twice = 2.0
        const val JuliaReal = -0.8
        const val JuliaImag = 0.156
        const val JuliaSwing = 0.08
        const val PhaseTurns = 0.17
        const val PhaseStepMs = 250L
        const val RestPhase = 0L
        const val PaletteSize = 4
        const val CyanBand = 2
    }
}
