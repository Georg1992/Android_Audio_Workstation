package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.georgv.audioworkstation.core.audio.TransportWaveformTrace
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

/**
 * Small transport display to the right of play and stop.
 * A message when one applies, a flat line at rest, and the waveform while audio plays.
 * The clock sits on the right of the same screen.
 * Subclass and override [Show] to change the screen.
 */
open class TransportMessageScreen {
    @Composable
    open fun Show(
        message: String?,
        visualizing: Boolean,
        positionMs: Long,
        durationMs: Long,
        modifier: Modifier = Modifier,
    ) {
        val shape = RoundedCornerShape(Dimens.SmallRadius)
        val heard by TransportWaveformTrace.samples.collectAsState()
        Box(
            modifier = modifier
                .height(Dimens.TransportButtonSize)
                .clip(shape)
                .background(AppColors.Bg, shape),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!visualizing && message != null) {
                        Text(
                            text = message,
                            style = AppText.TileSubtitle,
                            color = AppColors.Line,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = Dimens.TightGap),
                        )
                    } else {
                        val waveform = remember { TransportWaveform() }
                        waveform.Line(
                            samples = if (visualizing) heard else TransportWaveformTrace.rest(),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Text(
                    text = readout(positionMs, durationMs),
                    style = AppText.TileSubtitle,
                    color = AppColors.Line,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(end = Dimens.SmallRadius),
                )
            }
        }
    }

    fun readout(positionMs: Long, durationMs: Long): String {
        return "${screenClock(positionMs)}/${screenClock(durationMs)}"
    }
}

private const val ScreenClockRoundUpMs = 999L
private const val SecondsPerMinute = 60L

private fun screenClock(positionMs: Long): String {
    val totalSeconds = (positionMs.coerceAtLeast(0L) + ScreenClockRoundUpMs) / 1000L
    val minutes = totalSeconds / SecondsPerMinute
    val seconds = totalSeconds % SecondsPerMinute
    return minutes.toString().padStart(2, '0') + ":" + seconds.toString().padStart(2, '0')
}
