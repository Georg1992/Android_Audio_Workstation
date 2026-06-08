package com.georgv.audioworkstation.ui.screens.library

import com.georgv.audioworkstation.core.audio.ProjectMixdownState
import com.georgv.audioworkstation.data.db.entities.ProjectEntity

data class LibraryProjectItem(
    val project: ProjectEntity,
    val mixdown: ProjectMixdownState,
    val isPreviewPlaying: Boolean,
)

data class LibraryContent(
    val projects: List<LibraryProjectItem>,
)
