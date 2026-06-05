package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun SampleRateMismatchImportDialog(
    dialog: SampleRateMismatchDialogState,
    onImportWithResampling: () -> Unit,
    onCreateProject: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = AppColors.Bg,
        tonalElevation = 0.dp,
        title = {
            Text(
                text =
                    stringResource(
                        R.string.import_sample_rate_mismatch_message,
                        dialog.sourceSampleRateLabel,
                        dialog.projectSampleRateLabel,
                    ),
                style = AppText.TileTitle,
                color = AppColors.Line,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
            ) {
                Text(
                    text = stringResource(R.string.import_sample_rate_mismatch_resample_hint),
                    style = AppText.TileSubtitle,
                    color = AppColors.Line,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                ImportDialogChoiceButton(
                    text = stringResource(R.string.import_sample_rate_mismatch_resample),
                    fillColor = AppColors.Green,
                    onClick = onImportWithResampling,
                )
                ImportDialogChoiceButton(
                    text =
                        stringResource(
                            R.string.import_sample_rate_mismatch_create_project,
                            dialog.createProjectSampleRateLabel,
                        ),
                    fillColor = AppColors.Cyan,
                    onClick = onCreateProject,
                )
                ImportDialogChoiceButton(
                    text = stringResource(R.string.action_cancel),
                    fillColor = AppColors.SurfacePanel,
                    onClick = onCancel,
                )
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

@Composable
private fun ImportDialogChoiceButton(
    text: String,
    fillColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = AppColors.SurfacePanel,
        shadowElevation = Dimens.Stroke,
        shape = shape,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(fillColor)
                    .border(Dimens.Stroke, AppColors.Line, shape)
                    .padding(vertical = 14.dp, horizontal = Dimens.TileInnerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = AppText.TileTitle,
                color = AppColors.Line,
                textAlign = TextAlign.Center,
            )
        }
    }
}
