package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.painter.BitmapPainter

private enum class MusicNoteKind {
    Quarter,
    Eighth,
    Half,
    Whole,
    BeamedEighth,
}

private data class AnimatedNoteSpec(
    val kind: MusicNoteKind,
    val color: Color,
    val phase: Float,
    val lateral: Float,
    val lift: Float,
    val liftMultiplier: Float = 1f,
)

/** Per-note spawn offset and motion jitter — stable for this composable instance. */
private data class NoteMotionVariation(
    val spawnX: Float,
    val spawnY: Float,
    val lateralScale: Float,
    val liftScale: Float,
    val phaseOffset: Float,
    val driftX: Float,
    val wobbleStrength: Float,
    val wobbleCycles: Float,
    val scaleBias: Float,
)

private fun noteMotionForIndex(index: Int): NoteMotionVariation {
    val random = Random(index * 991 + 7)
    return NoteMotionVariation(
        spawnX = random.nextFloat() * 0.14f - 0.07f,
        spawnY = random.nextFloat() * 0.10f - 0.05f,
        lateralScale = random.nextFloat() * 0.18f + 0.91f,
        liftScale = random.nextFloat() * 0.14f + 0.93f,
        phaseOffset = random.nextFloat() * 0.05f - 0.025f,
        driftX = random.nextFloat() * 0.14f - 0.07f,
        wobbleStrength = random.nextFloat() * 0.45f + 0.65f,
        wobbleCycles = random.nextFloat() * 0.8f + 1.8f,
        scaleBias = random.nextFloat() * 0.08f - 0.04f,
    )
}

private val notePalette = listOf(
    AppColors.Green,
    AppColors.Yellow,
    AppColors.Cyan,
    AppColors.Pink,
    AppColors.Red,
)

/** Six notes in staggered lanes — ~1/6 cycle apart so fewer overlap mid-flight. */
private val animatedNotes = listOf(
    AnimatedNoteSpec(MusicNoteKind.Quarter, notePalette[0], phase = 0.00f, lateral = -0.78f, lift = 1.06f),
    AnimatedNoteSpec(MusicNoteKind.Eighth, notePalette[1], phase = 0.17f, lateral = 0.42f, lift = 1.20f),
    AnimatedNoteSpec(MusicNoteKind.Half, notePalette[2], phase = 0.34f, lateral = 0.78f, lift = 1.08f),
    AnimatedNoteSpec(
        kind = MusicNoteKind.BeamedEighth,
        color = AppColors.Pink,
        phase = 0.51f,
        lateral = -0.50f,
        lift = 1.48f,
        liftMultiplier = 1.15f,
    ),
    AnimatedNoteSpec(MusicNoteKind.Whole, notePalette[3], phase = 0.68f, lateral = 0.06f, lift = 1.14f),
    AnimatedNoteSpec(MusicNoteKind.Eighth, notePalette[4], phase = 0.85f, lateral = -0.30f, lift = 1.24f),
)

/** Looping loader: box image with accent-colored notes popping out of the opening. */
@Composable
fun AppMusicLoadingPlaceholder(
    message: String,
    modifier: Modifier = Modifier,
) {
    val boxPainter = rememberLoadingBoxPainter(R.drawable.loading_box3)
    var cycle by remember { mutableFloatStateOf(0f) }

    // Frame-clock loop keeps notes moving even when other work blocks composition setup.
    LaunchedEffect(Unit) {
        var startFrameNanos = withFrameNanos { it }
        while (true) {
            val frameTimeNanos = withFrameNanos { it }
            val elapsedMs = (frameTimeNanos - startFrameNanos) / 1_000_000L
            cycle = (elapsedMs % NOTE_CYCLE_MS).toFloat() / NOTE_CYCLE_MS.toFloat()
        }
    }

    val noteMotions = remember {
        animatedNotes.mapIndexed { index, _ -> noteMotionForIndex(index) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(width = 184.dp, height = 192.dp)) {
            if (boxPainter != null) {
                Image(
                    painter = boxPainter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val motionScale = size.minDimension * 0.94f
                val spawnCenter = Offset(
                    x = size.width * LOADING_BOX_OPENING_X_FRACTION,
                    y = size.height * LOADING_BOX_OPENING_Y_FRACTION,
                )

                animatedNotes.forEachIndexed { index, spec ->
                    val motion = noteMotions[index]
                    val t = ((cycle + spec.phase + motion.phaseOffset) % 1f).let { if (it < 0f) it + 1f else it }
                    val rise = easeOut(t)
                    val fade = when {
                        t < 0.72f -> 1f
                        else -> 1f - ((t - 0.72f) / 0.28f)
                    }
                    val wobble = sin(t * PI.toFloat() * motion.wobbleCycles) * motion.wobbleStrength * 3.dp.toPx()
                    val lateral = (spec.lateral * motion.lateralScale + motion.driftX * rise) * rise
                    val lift = spec.lift * spec.liftMultiplier * motion.liftScale
                    val noteCenter = Offset(
                        x = spawnCenter.x + motion.spawnX * motionScale * NOTE_SPAWN_SPREAD +
                            lateral * motionScale * NOTE_LATERAL_SPREAD,
                        y = spawnCenter.y + motion.spawnY * motionScale * NOTE_SPAWN_SPREAD -
                            lift * motionScale * NOTE_LIFT_SPREAD * rise + wobble,
                    )
                    drawMusicNote(
                        center = noteCenter,
                        kind = spec.kind,
                        fillColor = spec.color.copy(alpha = fade),
                        outlineColor = AppColors.Line.copy(alpha = fade),
                        scale = 0.85f + rise * 0.25f + motion.scaleBias,
                    )
                }
            }
        }

        Spacer(Modifier.height(Dimens.Gap))

        Text(
            text = message,
            style = AppText.TileSubtitle,
            color = AppColors.Line.copy(alpha = 0.62f),
        )
    }
}

private fun easeOut(t: Float): Float {
    val clamped = t.coerceIn(0f, 1f)
    return 1f - (1f - clamped) * (1f - clamped)
}

private const val NOTE_CYCLE_MS = 2_600L
private const val LOADING_BOX_OPENING_X_FRACTION = 0.54f
private const val LOADING_BOX_OPENING_Y_FRACTION = 0.33f
private const val NOTE_SPAWN_SPREAD = 0.12f
private const val NOTE_LATERAL_SPREAD = 0.42f
private const val NOTE_LIFT_SPREAD = 0.38f

private object LoadingBoxPainterCache {
    @DrawableRes
    var cachedDrawableId: Int = 0

    /** Process-wide decode cache — box bitmap is decoded off the main thread once. */
    var painter: Painter? = null
}

@Composable
private fun rememberLoadingBoxPainter(@DrawableRes drawableId: Int): Painter? {
    val context = LocalContext.current
    return produceState<Painter?>(
        initialValue = LoadingBoxPainterCache.painter?.takeIf { LoadingBoxPainterCache.cachedDrawableId == drawableId },
        drawableId,
    ) {
        val cached = LoadingBoxPainterCache.painter
        if (LoadingBoxPainterCache.cachedDrawableId == drawableId && cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val bitmap = BitmapFactory.decodeResource(context.resources, drawableId) ?: return@withContext null
            BitmapPainter(bitmap.asImageBitmap())
        }.also { decoded ->
            if (decoded != null) {
                LoadingBoxPainterCache.cachedDrawableId = drawableId
                LoadingBoxPainterCache.painter = decoded
            }
        }
    }.value
}

private fun DrawScope.drawMusicNote(
    center: Offset,
    kind: MusicNoteKind,
    fillColor: Color,
    outlineColor: Color,
    scale: Float,
) {
    if (kind == MusicNoteKind.BeamedEighth) {
        rotate(degrees = -18f, pivot = center) {
            drawBeamedEighthPair(
                center = center,
                headFill = fillColor,
                lineColor = outlineColor,
                scale = scale,
            )
        }
        return
    }

    val headRx = 5.dp.toPx() * scale
    val headRy = 3.6.dp.toPx() * scale
    val stemLen = 14.dp.toPx() * scale
    val outline = 1.6.dp.toPx()
    val headCenter = Offset(center.x - headRx * 0.15f, center.y)

    rotate(degrees = -18f, pivot = headCenter) {
        when (kind) {
            MusicNoteKind.Whole, MusicNoteKind.Half -> {
                drawOval(
                    color = AppColors.Bg,
                    topLeft = Offset(headCenter.x - headRx, headCenter.y - headRy),
                    size = androidx.compose.ui.geometry.Size(headRx * 2f, headRy * 2f),
                )
                drawOval(
                    color = fillColor,
                    topLeft = Offset(headCenter.x - headRx, headCenter.y - headRy),
                    size = androidx.compose.ui.geometry.Size(headRx * 2f, headRy * 2f),
                    style = Stroke(width = outline * 1.4f),
                )
                drawOval(
                    color = outlineColor,
                    topLeft = Offset(headCenter.x - headRx, headCenter.y - headRy),
                    size = androidx.compose.ui.geometry.Size(headRx * 2f, headRy * 2f),
                    style = Stroke(width = outline),
                )
            }
            MusicNoteKind.Quarter, MusicNoteKind.Eighth -> {
                drawOval(
                    color = fillColor,
                    topLeft = Offset(headCenter.x - headRx, headCenter.y - headRy),
                    size = androidx.compose.ui.geometry.Size(headRx * 2f, headRy * 2f),
                )
                drawOval(
                    color = outlineColor,
                    topLeft = Offset(headCenter.x - headRx, headCenter.y - headRy),
                    size = androidx.compose.ui.geometry.Size(headRx * 2f, headRy * 2f),
                    style = Stroke(width = outline),
                )
            }
        }

        if (kind != MusicNoteKind.Whole) {
            val stemX = headCenter.x + headRx * 0.72f
            val stemTop = headCenter.y - headRy - stemLen
            drawLine(
                color = outlineColor,
                start = Offset(stemX, headCenter.y - headRy * 0.2f),
                end = Offset(stemX, stemTop),
                strokeWidth = outline,
            )
        }

        if (kind == MusicNoteKind.Eighth) {
            val stemX = headCenter.x + headRx * 0.72f
            val stemTop = headCenter.y - headRy - stemLen
            val flagPath = Path().apply {
                moveTo(stemX, stemTop)
                quadraticTo(
                    stemX + 9.dp.toPx() * scale, stemTop + 4.dp.toPx() * scale,
                    stemX + 7.dp.toPx() * scale, stemTop + 11.dp.toPx() * scale,
                )
            }
            drawPath(flagPath, outlineColor, style = Stroke(width = outline))
        }
    }
}

private fun DrawScope.drawBeamedEighthPair(
    center: Offset,
    headFill: Color,
    lineColor: Color,
    scale: Float,
) {
    val headRx = 4.6.dp.toPx() * scale
    val headRy = 3.4.dp.toPx() * scale
    val spacing = 14.dp.toPx() * scale
    val stemLen = 15.dp.toPx() * scale
    val line = 1.6.dp.toPx()
    val beamThickness = 2.8.dp.toPx() * scale

    val leftHead = Offset(center.x - spacing / 2f, center.y)
    val rightHead = Offset(center.x + spacing / 2f, center.y)

    fun drawHead(headCenter: Offset) {
        val topLeft = Offset(headCenter.x - headRx, headCenter.y - headRy)
        val size = androidx.compose.ui.geometry.Size(headRx * 2f, headRy * 2f)
        drawOval(color = headFill, topLeft = topLeft, size = size)
        drawOval(color = lineColor, topLeft = topLeft, size = size, style = Stroke(width = line))
    }

    drawHead(leftHead)
    drawHead(rightHead)

    val leftStemX = leftHead.x + headRx * 0.72f
    val rightStemX = rightHead.x + headRx * 0.72f
    val stemTopY = leftHead.y - headRy - stemLen

    drawLine(
        color = lineColor,
        start = Offset(leftStemX, leftHead.y - headRy * 0.15f),
        end = Offset(leftStemX, stemTopY),
        strokeWidth = line,
    )
    drawLine(
        color = lineColor,
        start = Offset(rightStemX, rightHead.y - headRy * 0.15f),
        end = Offset(rightStemX, stemTopY),
        strokeWidth = line,
    )
    drawLine(
        color = lineColor,
        start = Offset(leftStemX, stemTopY + beamThickness / 2f),
        end = Offset(rightStemX, stemTopY + beamThickness / 2f),
        strokeWidth = beamThickness,
    )
}
