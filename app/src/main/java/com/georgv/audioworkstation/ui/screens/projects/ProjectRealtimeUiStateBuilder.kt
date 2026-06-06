package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.MasterPeakMeter
import com.georgv.audioworkstation.ui.components.TimelineMaxDurationMs
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
            sessionTrackIds = structural.sessionTrackIds,
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
                activeRecording = activeRecording,
                playheadPositionMs = playheadMs,
                extendVisibleTimelineForAllLoopedPlayback = extendForAllLoopedPlayback,
                extendVisibleTimelineForRecording = true,
            )
        } else {
            null
        }
    val visibleTimelineDurationMs =
        recordingTimelineProjection?.visibleTimelineDurationMs
            ?: visibleTimelineDurationMs(
                baseTimelineDurationMs = structural.timelineBaseDurationMs,
                playheadPositionMs = playheadMs,
                activeRecording = activeRecording,
                extendForAllLoopedPlayback = extendForAllLoopedPlayback,
                extendForRecording = extendForRecording,
            )
    val displayPlayheadMs =
        when {
            transportPhase == TransportPlaybackPhase.Recording -> {
                playheadMs.coerceAtLeast(0L)
            }
            extendForAllLoopedPlayback &&
                transportPhase == TransportPlaybackPhase.Playing -> {
                playheadMs.coerceIn(0L, TimelineMaxDurationMs)
            }
            else -> {
                timelinePlayheadClampedPositionMs(playheadMs, visibleTimelineDurationMs)
            }
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
        playheadPositionMs = displayPlayheadMs,
        recordingInputLevel = recordingLevel.coerceIn(0f, 1f),
        timelineVisibleDurationMs = visibleTimelineDurationMs,
        recordingTimelineClipsByTrackId = recordingTimelineProjection?.clipsByLaneId,
        recordingTimelineLaneLayoutDurationMs = recordingTimelineProjection?.laneLayoutDurationMs,
        masterPeakDbText = masterMeter.peakDbText,
        masterPeakIndicatorLevel = masterMeter.indicatorLevel,
    )
}
