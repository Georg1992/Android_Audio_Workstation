package com.georgv.audioworkstation.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProjectCard(
    card: ShownCard,
    clicks: ProjectCardClicks,
    actions: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.TileRadius)
    var bitmap by remember(card.imagePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(card.imagePath) {
        val path = card.imagePath
        bitmap = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
        }
    }
    val duration = card.durationMs
    val fraction = if (duration > 0) card.positionMs.toFloat() / duration.toFloat() else 0f
    var dragging by remember { mutableStateOf(false) }
    var heldFraction by remember { mutableStateOf<Float?>(null) }
    var positionAtRelease by remember { mutableStateOf<Int?>(null) }
    val held = heldFraction
    val displayed = (held ?: fraction).coerceIn(0f, 1f)
    val shownPositionMs = if (held != null && duration > 0) (displayed * duration).toInt() else card.positionMs
    LaunchedEffect(dragging, duration, card.positionMs) {
        if (duration <= 0) {
            dragging = false
            heldFraction = null
            positionAtRelease = null
            return@LaunchedEffect
        }
        if (dragging) return@LaunchedEffect
        val marked = positionAtRelease ?: return@LaunchedEffect
        if (card.positionMs != marked) {
            heldFraction = null
            positionAtRelease = null
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.ProjectCardHeight)
            .clip(shape)
            .border(Dimens.Stroke, AppColors.Line, shape),
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(AppColors.SurfacePanel))
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (image != null) AppColors.Bg.copy(alpha = 0.72f) else AppColors.SurfacePanel)
                    .padding(start = Dimens.TileInnerPadding, top = Dimens.TightGap, bottom = Dimens.TightGap),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f).padding(top = Dimens.PanelPadding)) {
                    Text(text = card.title, style = AppText.TileTitle, color = AppColors.Line, maxLines = 2)
                    val detail = card.detail
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = AppText.TileSubtitle,
                            color = AppColors.iconMuted,
                            maxLines = 1,
                        )
                    }
                    if (card.tags.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = Dimens.TightGap),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.TightGap),
                        ) {
                            card.tags.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(Dimens.SmallRadius),
                                    color = AppColors.SurfacePanel,
                                    modifier = Modifier.border(
                                        Dimens.Stroke,
                                        AppColors.Line,
                                        RoundedCornerShape(Dimens.SmallRadius),
                                    ),
                                ) {
                                    Text(
                                        text = tag,
                                        style = AppText.TileSubtitle,
                                        color = AppColors.Line,
                                        modifier = Modifier.padding(
                                            horizontal = Dimens.Gap,
                                            vertical = Dimens.TightGap,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    val status = card.status
                    if (status != null) {
                        Text(text = status, style = AppText.TileSubtitle, color = AppColors.iconMuted)
                    }
                    val progress = card.progress
                    if (card.loading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = Dimens.TightGap),
                            color = AppColors.Line,
                            trackColor = AppColors.iconMuted,
                        )
                    } else if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = Dimens.TightGap),
                            color = AppColors.Line,
                            trackColor = AppColors.iconMuted,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = clicks.onOpen) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = stringResource(R.string.cd_open_project),
                            tint = AppColors.Line,
                        )
                    }
                    actions()
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (image != null) AppColors.Bg.copy(alpha = 0.72f) else AppColors.SurfacePanel)
                    .padding(horizontal = Dimens.PanelPadding, vertical = Dimens.TightGap),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.TightGap),
                ) {
                    CardPlaybackButton(
                        role = CardPlaybackRole.Play,
                        engaged = !card.playing,
                        onClick = clicks.onPlay,
                    )
                    CardPlaybackButton(
                        role = CardPlaybackRole.Stop,
                        engaged = card.playing,
                        onClick = clicks.onStop,
                    )
                    Slider(
                        value = displayed,
                        onValueChange = { next ->
                            if (!dragging) clicks.onScrubStarted()
                            dragging = true
                            heldFraction = next
                        },
                        onValueChangeFinished = {
                            val released = heldFraction
                            positionAtRelease = card.positionMs
                            dragging = false
                            if (released != null && duration > 0) clicks.onSeek((released * duration).toInt())
                        },
                        enabled = duration > 0,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = AppColors.Line,
                            activeTrackColor = AppColors.Line,
                            inactiveTrackColor = AppColors.iconMuted,
                        ),
                    )
                }
                if (duration > 0) {
                    Text(
                        text = stringResource(
                            R.string.card_playback_clock,
                            playbackClock(shownPositionMs),
                            playbackClock(duration),
                        ),
                        style = AppText.TileSubtitle,
                        color = AppColors.iconMuted,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private enum class CardPlaybackRole {
    Play,
    Stop,
}

@Composable
private fun CardPlaybackButton(
    role: CardPlaybackRole,
    engaged: Boolean,
    onClick: () -> Unit,
) {
    val play = role == CardPlaybackRole.Play
    val fill = if (play) AppColors.Green else AppColors.Line
    val mark = if (play) AppColors.Line else AppColors.SurfaceRaised
    TrackActionButton(
        active = engaged,
        enabled = true,
        accentColor = mark,
        onClick = onClick,
        size = Dimens.TransportButtonSize,
        surfaceColor = AppColors.Bg,
        activeBackgroundColor = fill,
        activeBorderColor = AppColors.Line,
        activeIconColor = mark,
        showActiveGlow = false,
        showActiveLed = false,
    ) { tint ->
        Icon(
            imageVector = if (play) Icons.Filled.PlayArrow else Icons.Filled.Stop,
            contentDescription = stringResource(if (play) R.string.cd_play else R.string.cd_stop),
            tint = tint,
            modifier = Modifier.size(Dimens.TransportIconSize),
        )
    }
}

private const val MillisPerSecond = 1000
private const val SecondsPerMinute = 60

private fun playbackClock(positionMs: Int): String {
    val totalSeconds = positionMs.coerceAtLeast(0) / MillisPerSecond
    val minutes = totalSeconds / SecondsPerMinute
    val seconds = totalSeconds % SecondsPerMinute
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

data class ShownCard(
    val title: String,
    val tags: List<String>,
    val imagePath: String?,
    val positionMs: Int,
    val durationMs: Int,
    val status: String? = null,
    val progress: Float? = null,
    val detail: String? = null,
    val loading: Boolean = false,
    val playing: Boolean = false,
)

data class ProjectCardClicks(
    val onPlay: () -> Unit,
    val onStop: () -> Unit,
    val onScrubStarted: () -> Unit,
    val onSeek: (Int) -> Unit,
    val onOpen: () -> Unit,
)
