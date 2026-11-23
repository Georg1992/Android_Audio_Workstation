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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
            .graphicsLayer {
                this.shape = shape
                clip = true
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .clip(shape)
            .background(AppColors.SurfaceRaised, shape),
    ) {
        val picture = bitmap
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Image(
                painter = painterResource(R.drawable.card_background),
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape),
                contentScale = ContentScale.Crop,
            )
        }
        if (!opening.opened) {
            val scrimAlpha = if (picture != null) CardPhotoScrimAlpha else CardScrimAlpha
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(AppColors.SurfaceRaised.copy(alpha = scrimAlpha)),
            )
        }
        ProjectCardFace(
            card = card,
            opening = opening,
            actions = actions,
            playback = playback,
        )
    }
}

@Composable
private fun ProjectCardFace(
    card: ShownCard,
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
        if (opening.opened) {
            OpenedCard(
                card = card,
                opening = opening,
                actions = actions,
                playback = playback,
            )
        } else {
            IdentityPlate(
                card = card,
                onToggle = opening.onToggle,
                onOpen = opening.onOpen,
                actions = actions,
                opened = false,
            )
        }
    }
}

@Composable
private fun OpenedCard(
    card: ShownCard,
    opening: ProjectCardOpening,
    actions: @Composable RowScope.() -> Unit,
    playback: @Composable () -> Unit,
) {
    IdentityPlate(
        card = card,
        onToggle = opening.onToggle,
        onOpen = opening.onOpen,
        actions = actions,
        opened = true,
    )
    if (opening.mixAvailable) {
        playback()
    } else {
        UnavailableMixTransport()
    }
}

@Composable
private fun IdentityPlate(
    card: ShownCard,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    opened: Boolean,
) {
    if (!opened) {
        CollapsedIdentityPlate(card = card, onToggle = onToggle)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardPlate()
            .clickable(onClick = onToggle)
            .padding(Dimens.Gap),
        verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    style = AppText.TileTitle,
                    color = AppColors.Line,
                    maxLines = 2,
                    softWrap = true,
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
            }
            CardActions(onOpen = onOpen, actions = actions)
        }
        if (card.tags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
                verticalArrangement = Arrangement.spacedBy(Dimens.TightGap),
            ) {
                card.tags.forEach { tag ->
                    Text(
                        text = tag,
                        style = AppText.TileSubtitle,
                        color = AppColors.iconMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val description = card.description
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = CardDescription,
                color = AppColors.Line,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val status = card.status
        if (status != null) {
            Text(
                text = status,
                style = AppText.TileSubtitle,
                color = AppColors.iconMuted,
            )
        }
        val progress = card.progress
        if (card.loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = AppColors.Line,
                trackColor = AppColors.SurfacePanel,
            )
        } else if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = AppColors.Line,
                trackColor = AppColors.SurfacePanel,
            )
        }
    }
}

@Composable
private fun CollapsedIdentityPlate(
    card: ShownCard,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = CollapsedPlateMaxWidth)
            .wrapContentWidth(align = Alignment.Start)
            .cardPlate()
            .clickable(onClick = onToggle)
            .padding(Dimens.Gap),
        verticalArrangement = Arrangement.spacedBy(Dimens.TightGap),
    ) {
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
            )
        }
        val description = card.description
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = CollapsedCardDescription,
                color = AppColors.Line,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (card.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
                verticalArrangement = Arrangement.spacedBy(Dimens.TightGap),
            ) {
                card.tags.forEach { tag ->
                    Text(
                        text = tag,
                        style = AppText.TileSubtitle,
                        color = AppColors.iconMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardActions(
    onOpen: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.TightGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardActionButton(onClick = onOpen, fill = AppColors.SurfaceRaised) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = stringResource(R.string.cd_open_project),
                tint = AppColors.Line,
            )
        }
        actions()
    }
}

@Composable
private fun UnavailableMixTransport() {
    TransportCluster(
        modifier = Modifier.cardPlate(),
        inputLocked = true,
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    paint = TransportButtonPaint(AppColors.SurfaceRaised, AppColors.SurfaceRaised),
                    enabled = false,
                    isActive = false,
                    onClick = {},
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.cd_play),
                        tint = AppColors.Line,
                        modifier = Modifier.size(Dimens.TransportIconSize),
                    )
                }
                TransportButton(
                    paint = TransportButtonPaint(AppColors.SurfaceRaised, AppColors.SurfaceRaised),
                    enabled = false,
                    isActive = false,
                    onClick = {},
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.cd_stop),
                        tint = AppColors.Line,
                        modifier = Modifier.size(Dimens.TransportIconSize),
                    )
                }
                val messageScreen = remember { TransportMessageScreen() }
                messageScreen.Show(
                    message = stringResource(R.string.library_no_mix_yet),
                    visualizing = false,
                    positionMs = 0L,
                    durationMs = 0L,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        timeline = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.card_no_mix),
                    style = AppText.TileSubtitle,
                    color = AppColors.iconMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Dimens.Gap),
                )
            }
        },
    )
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

private fun Modifier.cardPlate(): Modifier {
    val shape = RoundedCornerShape(Dimens.TileRadius)
    return this.clip(shape).background(AppColors.SurfaceRaised.copy(alpha = CardPlateAlpha), shape)
}

private const val PinArriveMs = 100
private const val CardScrimAlpha = 0.28f
private const val CardPhotoScrimAlpha = 0.58f
private const val CardPlateAlpha = 0.5f
private val CollapsedPlateMaxWidth = 220.dp

private val CardDescription = AppText.TileSubtitle.copy(
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
)

private val CollapsedCardDescription = AppText.TileSubtitle.copy(
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

data class ShownCard(
    val title: String,
    val tags: List<String>,
    val imagePath: String?,
    val status: String? = null,
    val progress: Float? = null,
    val detail: String? = null,
    val description: String = "",
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
    message: String? = null,
) {
    CardPlaybackStrip(
        playing = playing,
        positionMs = positionMs,
        durationMs = durationMs,
        onPlay = onPlay,
        onStop = onStop,
        onScrubStarted = onScrubStarted,
        onSeek = onSeek,
        message = message,
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
    message: String? = null,
) {
    CardPlaybackStrip(
        playing = playing,
        positionMs = positionMs,
        durationMs = durationMs,
        onPlay = onPlay,
        onStop = onStop,
        onScrubStarted = onScrubStarted,
        onSeek = onSeek,
        message = message,
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
    message: String?,
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
    TransportCluster(
        modifier = Modifier.cardPlate(),
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    paint = TransportButtonPaint(AppColors.Green, AppColors.SurfaceRaised),
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
                    paint = TransportButtonPaint(AppColors.SurfaceRaised, AppColors.SurfaceRaised),
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
                val messageScreen = remember { TransportMessageScreen() }
                messageScreen.Show(
                    message = message,
                    visualizing = playing,
                    positionMs = shownPositionMs.toLong(),
                    durationMs = durationMs.toLong(),
                    modifier = Modifier.weight(1f),
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
