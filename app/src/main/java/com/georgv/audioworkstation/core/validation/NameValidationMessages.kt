package com.georgv.audioworkstation.core.validation

import androidx.annotation.StringRes
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage

/**
 * Maps a [NameValidationError] into a localized [UiMessage] using the given blank/too-long string
 * resources. Kept here so callers (Project / Track / future entities) can pick the right copy
 * while sharing the same mapping logic.
 */
fun NameValidationError.toUiMessage(
    @StringRes blankResId: Int,
    @StringRes tooLongResId: Int
): UiMessage = when (this) {
    NameValidationError.Blank -> UiMessage(blankResId)
    is NameValidationError.TooLong -> UiMessage(tooLongResId, maxLength)
}

fun NameValidationError.toProjectNameUiMessage(): UiMessage = toUiMessage(
    blankResId = R.string.error_project_name_blank,
    tooLongResId = R.string.error_project_name_too_long
)

fun NameValidationError.toTrackNameUiMessage(): UiMessage = toUiMessage(
    blankResId = R.string.error_track_name_blank,
    tooLongResId = R.string.error_track_name_too_long
)
