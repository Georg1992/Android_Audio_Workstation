package com.georgv.audioworkstation.ui.screens.library

import com.georgv.audioworkstation.data.db.entities.ProjectEntity

data class LibraryContent(
    val projects: List<ProjectEntity>,
)
