package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.modifiers.consumeAllPointers
import com.georgv.audioworkstation.ui.theme.Alphas
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun ImportAudioButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    inputLocked: Boolean = false
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)

    Box(
        modifier = modifier
            .size(Dimens.TransportButtonSize)
            .clip(shape)
            .background(AppColors.Bg)
            .border(Dimens.Stroke, AppColors.Line, shape)
            .consumeAllPointers(enabled = inputLocked),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled && !inputLocked
        ) {
            Box(modifier = Modifier.alpha(if (enabled && !inputLocked) 1f else Alphas.Disabled)) {
                Icon(
                    imageVector = Icons.Filled.FileUpload,
                    contentDescription = stringResource(R.string.cd_import_audio),
                    tint = AppColors.Line
                )
            }
        }
    }
}
