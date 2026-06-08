package com.georgv.audioworkstation.ui.screens.library

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.ProjectMixdownState

enum class LibraryCardBodyClickOutcome {
    IgnoredWhileMixing,
    NoMixAvailable,
    TogglePreview,
}

fun resolveLibraryCardBodyClick(
    mixdown: ProjectMixdownState,
    @Suppress("UNUSED_PARAMETER") isCurrentlyPlaying: Boolean,
    mixFileExists: Boolean,
): LibraryCardBodyClickOutcome {
    if (mixdown.isMixing) return LibraryCardBodyClickOutcome.IgnoredWhileMixing
    if (!mixdown.hasMixPreview || !mixFileExists) return LibraryCardBodyClickOutcome.NoMixAvailable
    return LibraryCardBodyClickOutcome.TogglePreview
}

fun libraryCardSubtitleResId(
    mixdown: ProjectMixdownState,
    isCurrentlyPlaying: Boolean,
    mixFileExists: Boolean,
): Int {
    if (mixdown.isMixing) return R.string.library_mixing_progress
    if (!mixdown.hasMixPreview || !mixFileExists) return R.string.library_no_mix_yet
    return if (isCurrentlyPlaying) {
        R.string.library_preview_playing_hint
    } else {
        R.string.library_preview_mix_hint
    }
}
