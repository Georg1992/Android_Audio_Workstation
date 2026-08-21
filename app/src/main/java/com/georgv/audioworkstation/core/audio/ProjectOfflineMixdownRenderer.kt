package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.track.selectedPlayableTracks
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.core.audio.mixdownTimelineEndMs
import com.georgv.audioworkstation.core.diagnostics.TransportFrameDiagnostics
import javax.inject.Inject
import javax.inject.Singleton

sealed class OfflineMixdownResult {
    data class Success(val outputPath: String) : OfflineMixdownResult()

    data object NoPlayableTracks : OfflineMixdownResult()

    data object WriteFailed : OfflineMixdownResult()

    data object Cancelled : OfflineMixdownResult()
}

/**
 * Kotlin orchestration entry for project mixdown. All audio rendering is native.
 *
 * Mixdown uses [TimelineClipStartSource.VisualPlacement] — exported audio follows the project
 * timeline the user sees, not live overdub scheduling correction.
 */
@Singleton
class ProjectOfflineMixdownRenderer @Inject constructor(
    private val mixdown: MixdownPort,
) {

    suspend fun render(
        project: ProjectEntity,
        tracks: List<TrackEntity>,
        selectedTrackIds: Set<String>,
        outputPath: String,
        onProgress: (Float) -> Unit,
    ): OfflineMixdownResult {
        val mixTracks = selectedPlayableTracks(tracks, selectedTrackIds)
        if (mixTracks.isEmpty()) {
            return OfflineMixdownResult.NoPlayableTracks
        }

        val sessionEndMs = mixdownTimelineEndMs(tracks, selectedTrackIds)
        val audibleStart =
            TransportTimelinePolicy.playbackStartPositionMsForTracks(
                scrubbedPlayheadMs = 0L,
                timelineVisibleDurationMs = sessionEndMs,
                tracks = mixTracks,
            )
        val spec =
            project.toVisualTimelinePlaybackSpec(mixTracks)?.copy(
                startPositionMs = audibleStart.value,
                sessionTimelineEndMs = sessionEndMs,
            ) ?: return OfflineMixdownResult.WriteFailed
        TransportFrameDiagnostics.logPlaybackArm(spec, transportStartFrame = -1L, transportFrame = -1L)

        return when (
            val result =
                mixdown.renderOfflineMixdown(
                    spec = spec,
                    outputPath = outputPath,
                    onProgress = onProgress,
                )
        ) {
            is MixdownResult.Success -> OfflineMixdownResult.Success(result.outputPath)
            MixdownResult.Cancelled -> OfflineMixdownResult.Cancelled
            MixdownResult.Failed -> OfflineMixdownResult.WriteFailed
        }
    }
}
