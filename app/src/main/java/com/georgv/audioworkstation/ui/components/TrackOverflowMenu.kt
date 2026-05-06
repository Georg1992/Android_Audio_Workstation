package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

/** Menu rows only (no framed panel); embed in inline surface or DropdownMenu container. */
@Composable
fun TrackOverflowMenuBody(
    onDelete: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemShape = RoundedCornerShape(Dimens.MediumRadius)
    Column(
        modifier = modifier.wrapContentWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.Stroke),
    ) {
        TrackMenuRow(
            text = stringResource(R.string.action_delete),
            shape = itemShape,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = AppColors.Line
                )
            },
            onClick = onDelete
        )

        TrackMenuRow(
            text = stringResource(R.string.action_rename),
            shape = itemShape,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    tint = AppColors.Line
                )
            },
            onClick = onRename
        )
    }
}

@Composable
fun TrackOverflowMenu(
    onDelete: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier
) {
    val menuShape = RoundedCornerShape(Dimens.TileRadius)
    TrackOverflowMenuBody(
        onDelete = onDelete,
        onRename = onRename,
        modifier =
            modifier
                .wrapContentWidth()
                .background(AppColors.Bg, menuShape)
                .border(Dimens.Stroke, AppColors.Line, menuShape)
                .padding(Dimens.Stroke),
    )
}

@Composable
private fun TrackMenuRow(
    text: String,
    shape: RoundedCornerShape,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .wrapContentWidth()
            .heightIn(min = Dimens.MenuRowMinHeight)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.Gap, vertical = Dimens.SmallRadius),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        icon()
        Spacer(Modifier.width(Dimens.PanelPadding))
        Text(
            text = text,
            color = AppColors.Line,
            maxLines = 1
        )
    }
}
