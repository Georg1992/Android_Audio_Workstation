package com.georgv.audioworkstation.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
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
            .shadow(elevation = Dimens.PanelElevation, shape = shape, clip = false)
            .clip(shape)
            .background(AppColors.SurfaceRaised),
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
                        color = AppColors.Pink,
                        trackColor = AppColors.SurfacePanel,
                    )
                } else if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Dimens.TightGap),
                        color = AppColors.Cyan,
                        trackColor = AppColors.SurfacePanel,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.Gap),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Gap, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CardActionButton(onClick = opening.onOpen, fill = AppColors.GreenSoft) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = stringResource(R.string.cd_open_project),
                            tint = AppColors.Line,
                        )
                    }
                    actions()
                    CardActionButton(onClick = opening.onToggle, fill = AppColors.Bg) {
                        Icon(
                            imageVector = Icons.Filled.ExpandLess,
                            contentDescription = stringResource(R.string.cd_close_card),
                            tint = AppColors.Line,
                        )
                    }
                }
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
            .shadow(elevation = Dimens.ControlElevation, shape = shape, clip = false)
            .clip(shape)
            .background(AppColors.SurfacePanel),
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
        color = AppColors.YellowSoft,
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
    fill: Color,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.SmallRadius)
    Box(
        modifier = Modifier
            .size(Dimens.IconTileSize)
            .alpha(if (enabled) 1f else AppOpacity.disabled)
            .transportControlChrome(
                shape = shape,
                fill = fill,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private const val PinArriveMs = 100

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
    var heldMs by remember { mutableStateOf<Int?>(null) }
    var pinnedMs by remember { mutableStateOf<Int?>(null) }
    val finger = heldMs
    val pin = pinnedMs
    val shownPositionMs = when {
        finger != null && durationMs > 0 -> finger.coerceIn(0, durationMs)
        pin != null -> pin.coerceIn(0, durationMs)
        else -> positionMs
    }
    LaunchedEffect(dragging, durationMs, positionMs, pinnedMs) {
        if (durationMs <= 0) {
            dragging = false
            heldMs = null
            pinnedMs = null
            return@LaunchedEffect
        }
        if (dragging) return@LaunchedEffect
        val marked = pinnedMs ?: return@LaunchedEffect
        val distance = if (positionMs >= marked) positionMs - marked else marked - positionMs
        if (distance <= PinArriveMs) {
            pinnedMs = null
            heldMs = null
        }
    }
    val clock = stringResource(
        R.string.card_playback_clock,
        formatTimelineDuration(shownPositionMs.toLong()),
        formatTimelineDuration(durationMs.toLong()),
    )
    TransportCluster(
        clock = TransportClock(
            label = formatTimelineDuration(shownPositionMs.toLong()),
            description = clock,
        ),
        buttons = {
            TransportButton(
                paint = TransportButtonPaint(AppColors.Green, AppColors.GreenSoft),
                enabled = true,
                isActive = playing,
                onClick = onPlay,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play),
                    tint = AppColors.Line,
                    modifier = Modifier.size(Dimens.TransportIconSize),
                )
            }
            TransportButton(
                paint = TransportButtonPaint(AppColors.Yellow, AppColors.YellowSoft),
                enabled = true,
                isActive = false,
                onClick = onStop,
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.cd_stop),
                    tint = AppColors.Line,
                    modifier = Modifier.size(Dimens.TransportIconSize),
                )
            }
        },
        timeline = {
            MixTransportTimeline(
            playheadPositionMs = shownPositionMs.toLong(),
            timelineDurationMs = durationMs.toLong(),
            gestures = MixTimelineGestures(
                onStarted = {
                    if (!dragging) {
                        pinnedMs = null
                        onScrubStarted?.invoke()
                    }
                    dragging = true
                },
                onCancelled = {
                    dragging = false
                    heldMs = null
                },
                onPreview = { next -> heldMs = next.toInt() },
                onCommit = { next ->
                    dragging = false
                    heldMs = null
                    if (durationMs > 0) {
                        val target = next.toInt().coerceIn(0, durationMs)
                        pinnedMs = target
                        onSeek(target)
                    }
                },
            ),
            inputLocked = durationMs <= 0,
            modifier = Modifier.fillMaxSize(),
        )
        },
    )
}
