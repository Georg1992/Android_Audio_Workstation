package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.WaveformState

internal data class ProjectScreenSnapshot(
    val projectId: String?,
    val project: ProjectEntity?,
    val tracks: List<TrackEntity>,
    val selectedTrackIds: Set<String>,
    val sessionTrackIds: Set<String>,
    val playbackSessionActive: Boolean,
    val recordingTrackId: String?,
    val waveformStatesByTrackId: Map<String, WaveformState>,
    val isRecordingStartup: Boolean,
    val importProgressByTrackId: Map<String, Float> = emptyMap(),
)
