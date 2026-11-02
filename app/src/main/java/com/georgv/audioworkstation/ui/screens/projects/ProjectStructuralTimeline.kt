package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.ui.components.ActiveRecordingTimelineClip
import com.georgv.audioworkstation.ui.components.ProjectTimelineProjection
import com.georgv.audioworkstation.ui.components.buildProjectTimelineProjection

internal fun buildStructuralTimelineProjection(
    screen: ProjectScreenSnapshot,
): ProjectTimelineProjection {
    val structuralRecording =
        screen.recordingTrackId?.let { recordingId ->
            val track = screen.tracks.find { it.id == recordingId } ?: return@let null
            ActiveRecordingTimelineClip(
                trackId = recordingId,
                startOffsetMs = track.timelineStartOffsetMs.coerceAtLeast(0L),
                elapsedMs = 0L,
            )
        }
    return buildProjectTimelineProjection(
        tracks = screen.tracks,
        waveformStatesByTrackId = screen.waveformStatesByTrackId,
        selectedTrackIds = screen.selectedTrackIds,
        activeRecording = structuralRecording,
        playheadPositionMs = 0L,
        extendVisibleTimelineForAllLoopedPlayback = false,
        extendVisibleTimelineForRecording = false,
        importProgressByTrackId = screen.importProgressByTrackId,
    )
}

internal fun ProjectUiState.mergeRealtime(realtime: ProjectRealtimeUiState): ProjectUiState =
    copy(
        playheadPositionMs = realtime.globalPlayheadPositionMs,
        recordingInputLevel = realtime.recordingInputLevel,
        timelineVisibleDurationMs = realtime.timelineVisibleDurationMs,
        timelineClipsByTrackId = realtime.recordingTimelineClipsByTrackId ?: timelineClipsByTrackId,
        timelineLaneLayoutDurationMs =
            realtime.recordingTimelineLaneLayoutDurationMs ?: timelineLaneLayoutDurationMs,
        masterPeakDbText = realtime.masterPeakDbText,
        masterPeakIndicatorLevel = realtime.masterPeakIndicatorLevel,
    )
