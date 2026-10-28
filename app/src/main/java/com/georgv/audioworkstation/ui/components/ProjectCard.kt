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
                    if (progress != null) {
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
                ) {
                    IconButton(onClick = clicks.onPlay) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.cd_play),
                            tint = AppColors.Line,
                        )
                    }
                    IconButton(onClick = clicks.onStop) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.cd_stop),
                            tint = AppColors.Line,
                        )
                    }
                    Slider(
                        value = fraction.coerceIn(0f, 1f),
                        onValueChange = { next ->
                            if (duration > 0) clicks.onSeek((next * duration).toInt())
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
            }
        }
    }
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
)

data class ProjectCardClicks(
    val onPlay: () -> Unit,
    val onStop: () -> Unit,
    val onSeek: (Int) -> Unit,
    val onOpen: () -> Unit,
)
