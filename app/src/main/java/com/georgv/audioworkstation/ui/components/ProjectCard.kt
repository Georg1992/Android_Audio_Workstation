package com.georgv.audioworkstation.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppOpacity
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ProjectCardOpening(
    val opened: Boolean,
    val mixAvailable: Boolean,
    val onToggle: () -> Unit,
    val onOpen: () -> Unit,
)

@Composable
fun ProjectCard(
    card: ShownCard,
    opening: ProjectCardOpening,
    actions: @Composable RowScope.() -> Unit,
    playback: @Composable () -> Unit,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
            .clip(shape)
            .background(AppColors.SurfacePanel)
            .border(Dimens.Stroke, AppColors.Line, shape),
    ) {
        if (opening.opened) {
            OpenedProjectCard(
                card = card,
                bitmap = bitmap,
                opening = opening,
                actions = actions,
                playback = playback,
            )
        } else {
            ProjectCardShortcut(
                card = card,
                bitmap = bitmap,
                onClick = opening.onToggle,
            )
        }
    }
}

@Composable
private fun ProjectCardShortcut(
    card: ShownCard,
    bitmap: ImageBitmap?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
    ) {
        CardArt(bitmap = bitmap, size = Dimens.CardShortcutArt)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = card.title,
                style = AppText.TileTitle,
                color = AppColors.Line,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = card.detail
            if (detail != null) {
                Text(
                    text = detail,
                    style = AppText.TileSubtitle,
                    color = AppColors.iconMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OpenedProjectCard(
    card: ShownCard,
    bitmap: ImageBitmap?,
    opening: ProjectCardOpening,
    actions: @Composable RowScope.() -> Unit,
    playback: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.TileInnerPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
            verticalAlignment = Alignment.Top,
        ) {
            CardArt(bitmap = bitmap, size = Dimens.CardOpenArt)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    style = AppText.TileTitle,
                    color = AppColors.Line,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = card.detail
                if (detail != null) {
                    Text(
                        text = detail,
                        style = AppText.TileSubtitle,
                        color = AppColors.iconMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Dimens.TightGap),
                    )
                }
                if (card.tags.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Dimens.Gap),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
                        verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
                    ) {
                        card.tags.forEach { tag ->
                            CardTag(tag)
                        }
                    }
                }
                val status = card.status
                if (status != null) {
                    Text(
                        text = status,
                        style = AppText.TileSubtitle,
                        color = AppColors.iconMuted,
                        modifier = Modifier.padding(top = Dimens.Gap),
                    )
                }
                val progress = card.progress
                if (card.loading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Dimens.TightGap),
                        color = AppColors.Line,
                        trackColor = AppColors.iconMuted,
                    )
                } else if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Dimens.TightGap),
                        color = AppColors.Line,
                        trackColor = AppColors.iconMuted,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardActionButton(onClick = opening.onOpen) {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = stringResource(R.string.cd_open_project),
                    tint = AppColors.Line,
                )
            }
            actions()
            CardActionButton(onClick = opening.onToggle) {
                Icon(
                    imageVector = Icons.Filled.ExpandLess,
                    contentDescription = stringResource(R.string.cd_close_card),
                    tint = AppColors.Line,
                )
            }
        }
        if (opening.mixAvailable) {
            playback()
        }
    }
}

@Composable
private fun CardArt(
    bitmap: ImageBitmap?,
    size: Dp,
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(AppColors.SurfaceRaised)
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
        }
    }
}

@Composable
private fun CardTag(tag: String) {
    val shape = RoundedCornerShape(Dimens.SmallRadius)
    Surface(
        shape = shape,
        color = AppColors.Bg,
        modifier = Modifier.border(Dimens.Stroke, AppColors.Line, shape),
    ) {
        Text(
            text = tag,
            style = AppText.TileSubtitle,
            color = AppColors.Line,
            modifier = Modifier.padding(horizontal = Dimens.Gap, vertical = Dimens.TightGap),
        )
    }
}

@Composable
fun CardActionButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.SmallRadius)
    Box(
        modifier = Modifier
            .size(Dimens.IconTileSize)
            .alpha(if (enabled) 1f else AppOpacity.disabled)
            .clip(shape)
            .background(AppColors.Bg)
            .border(Dimens.Stroke, AppColors.Line, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
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
    val fill by animateColorAsState(
        targetValue = when {
            engaged && play -> AppColors.Green
            engaged -> AppColors.Yellow
            else -> AppColors.Bg
        },
        animationSpec = tween(durationMillis = TransportFillMs),
        label = "cardTransportFill",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) TransportPressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "cardTransportPress",
    )
    Box(
        modifier = Modifier
            .size(Dimens.TransportButtonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(fill)
            .border(Dimens.Stroke, AppColors.Line, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (play) Icons.Filled.PlayArrow else Icons.Filled.Stop,
            contentDescription = stringResource(if (play) R.string.cd_play else R.string.cd_stop),
            tint = AppColors.Line,
            modifier = Modifier.size(Dimens.TransportIconSize),
        )
    }
}

private const val MillisPerSecond = 1000
private const val SecondsPerMinute = 60
private const val PinArriveMs = 100
private const val TransportFillMs = 160
private const val TransportPressedScale = 0.94f

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
    val status: String? = null,
    val progress: Float? = null,
    val detail: String? = null,
    val loading: Boolean = false,
)

@Composable
fun LibraryCardPlayback(
    playing: Boolean,
    positionMs: Int,
    durationMs: Int,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onScrubStarted: () -> Unit,
    onSeek: (Int) -> Unit,
) {
    CardPlaybackStrip(
        playing = playing,
        positionMs = positionMs,
        durationMs = durationMs,
        onPlay = onPlay,
        onStop = onStop,
        onScrubStarted = onScrubStarted,
        onSeek = onSeek,
    )
}

@Composable
fun CommunityCardPlayback(
    playing: Boolean,
    positionMs: Int,
    durationMs: Int,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onScrubStarted: () -> Unit,
    onSeek: (Int) -> Unit,
) {
    CardPlaybackStrip(
        playing = playing,
        positionMs = positionMs,
        durationMs = durationMs,
        onPlay = onPlay,
        onStop = onStop,
        onScrubStarted = onScrubStarted,
        onSeek = onSeek,
    )
}

@Composable
private fun CardPlaybackStrip(
    playing: Boolean,
    positionMs: Int,
    durationMs: Int,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onScrubStarted: (() -> Unit)?,
    onSeek: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var heldFraction by remember { mutableStateOf<Float?>(null) }
    var pinnedMs by remember { mutableStateOf<Int?>(null) }
    val finger = heldFraction
    val pin = pinnedMs
    val shownPositionMs = when {
        finger != null && durationMs > 0 -> (finger.coerceIn(0f, 1f) * durationMs).toInt()
        pin != null -> pin.coerceIn(0, durationMs)
        else -> positionMs
    }
    val displayed = if (durationMs > 0) shownPositionMs.toFloat() / durationMs.toFloat() else 0f
    LaunchedEffect(dragging, durationMs, positionMs, pinnedMs) {
        if (durationMs <= 0) {
            dragging = false
            heldFraction = null
            pinnedMs = null
            return@LaunchedEffect
        }
        if (dragging) return@LaunchedEffect
        val marked = pinnedMs ?: return@LaunchedEffect
        val distance = if (positionMs >= marked) positionMs - marked else marked - positionMs
        if (distance <= PinArriveMs) {
            pinnedMs = null
            heldFraction = null
        }
    }
    val shape = RoundedCornerShape(Dimens.TransportPanelRadius)
    val clock = stringResource(
        R.string.card_playback_clock,
        playbackClock(shownPositionMs),
        playbackClock(durationMs),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.Bg)
            .border(Dimens.Stroke, AppColors.Line, shape)
            .padding(Dimens.Gap),
        verticalArrangement = Arrangement.spacedBy(Dimens.PanelPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardPlaybackButton(
                role = CardPlaybackRole.Play,
                engaged = !playing,
                onClick = onPlay,
            )
            CardPlaybackButton(
                role = CardPlaybackRole.Stop,
                engaged = playing,
                onClick = onStop,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            TransportClock(text = playbackClock(shownPositionMs), align = TextAlign.End)
            Slider(
                value = displayed,
                onValueChange = { next ->
                    val marked = pinnedMs
                    val restating = !dragging && marked != null && durationMs > 0 &&
                        (next * durationMs).toInt() == marked
                    if (!restating) {
                        if (!dragging) {
                            pinnedMs = null
                            onScrubStarted?.invoke()
                        }
                        dragging = true
                        heldFraction = next
                    }
                },
                onValueChangeFinished = {
                    val released = heldFraction
                    dragging = false
                    if (released != null && durationMs > 0) {
                        val target = (released * durationMs).toInt()
                        pinnedMs = target
                        heldFraction = null
                        onSeek(target)
                    } else {
                        heldFraction = null
                    }
                },
                enabled = durationMs > 0,
                modifier = Modifier
                    .weight(1f)
                    .semantics { stateDescription = clock },
                colors = SliderDefaults.colors(
                    thumbColor = AppColors.Line,
                    activeTrackColor = AppColors.Green,
                    inactiveTrackColor = AppColors.iconMuted,
                    disabledThumbColor = AppColors.iconMuted,
                    disabledActiveTrackColor = AppColors.iconMuted,
                    disabledInactiveTrackColor = AppColors.iconMuted,
                ),
            )
            TransportClock(text = playbackClock(durationMs), align = TextAlign.Start)
        }
    }
}

@Composable
private fun TransportClock(
    text: String,
    align: TextAlign,
) {
    Text(
        text = text,
        style = AppText.TileSubtitle,
        color = AppColors.labelEmphasis,
        textAlign = align,
        maxLines = 1,
        modifier = Modifier.widthIn(min = Dimens.TransportButtonSize),
    )
}
