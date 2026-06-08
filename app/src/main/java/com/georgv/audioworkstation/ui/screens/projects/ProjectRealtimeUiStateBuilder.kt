package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.MasterPeakMeter
import com.georgv.audioworkstation.ui.components.TimelineMinimumBaseDurationMs
import com.georgv.audioworkstation.ui.components.buildProjectTimelineProjection
import com.georgv.audioworkstation.ui.components.shouldExtendVisibleTimelineForAllLoopedPlayback
import com.georgv.audioworkstation.ui.components.timelinePlayheadClampedPositionMs
import com.georgv.audioworkstation.ui.components.visibleTimelineDurationMs

internal fun buildProjectRealtimeUiState(
    playheadMs: Long,
    recordingLevel: Float,
    peakHoldLinear: Float,
    structural: ProjectUiState,
    transportPhase: TransportPlaybackPhase,
): ProjectRealtimeUiState {
    val extendForAllLoopedPlayback =
        shouldExtendVisibleTimelineForAllLoopedPlayback(
            playbackSessionActive = structural.playbackSessionActive,
            selectedTrackIds = structural.selectedTrackIds,
            tracks = structural.tracks,
        )
    val extendForRecording = transportPhase == TransportPlaybackPhase.Recording
    val activeRecording =
        activeRecordingTimelineClip(
            tracks = structural.tracks,
            recordingTrackId = structural.recordingTrackId,
            playheadMs = playheadMs,
        )
    val recordingTimelineProjection =
        if (extendForRecording && activeRecording != null) {
            buildProjectTimelineProjection(
                tracks = structural.tracks,
                waveformStatesByTrackId = structural.waveformStatesByTrackId,
                selectedTrackIds = structural.selectedTrackIds,
                activeRecording = activeRecording,
                playheadPositionMs = playheadMs,
                extendVisibleTimelineForAllLoopedPlayback = extendForAllLoopedPlayback,
                extendVisibleTimelineForRecording = true,
            )
        } else {
            null
        }
    val timelineVisibleDurationMs =
        recordingTimelineProjection?.visibleTimelineDurationMs
            ?: visibleTimelineDurationMs(
                baseTimelineDurationMs = structural.timelineBaseDurationMs,
                playheadPositionMs = playheadMs,
                activeRecording = activeRecording,
                extendForAllLoopedPlayback = extendForAllLoopedPlayback,
                extendForRecording = extendForRecording,
            )
    val globalPlayheadPositionMs =
        when (transportPhase) {
            TransportPlaybackPhase.Recording -> playheadMs.coerceAtLeast(0L)
            else ->
                timelinePlayheadClampedPositionMs(
                    playheadMs,
                    timelineVisibleDurationMs.coerceAtLeast(TimelineMinimumBaseDurationMs),
                )
        }
    val showSessionMasterPeak =
        transportPhase == TransportPlaybackPhase.Playing ||
            transportPhase == TransportPlaybackPhase.Paused
    val masterMeter =
        MasterPeakMeter.fromPeakHoldLinear(
            peakLinear = peakHoldLinear,
            isStopped = !showSessionMasterPeak,
        )
    return ProjectRealtimeUiState(
        playheadPositionMs = playheadMs.coerceAtLeast(0L),
        globalPlayheadPositionMs = globalPlayheadPositionMs,
        recordingInputLevel = recordingLevel.coerceIn(0f, 1f),
        timelineVisibleDurationMs = timelineVisibleDurationMs,
        recordingTimelineClipsByTrackId = recordingTimelineProjection?.clipsByLaneId,
        recordingTimelineLaneLayoutDurationMs = recordingTimelineProjection?.laneLayoutDurationMs,
        masterPeakDbText = masterMeter.peakDbText,
        masterPeakIndicatorLevel = masterMeter.indicatorLevel,
    )
}
