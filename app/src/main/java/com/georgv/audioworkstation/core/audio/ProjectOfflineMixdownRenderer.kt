package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.track.playbackStartPositionMsForTracks
import com.georgv.audioworkstation.core.track.selectedPlayableTracks
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.mixdownTimelineEndMs
import com.georgv.audioworkstation.ui.diagnostics.TransportFrameDiagnostics
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
 */
@Singleton
class ProjectOfflineMixdownRenderer @Inject constructor(
    private val audioController: AudioController,
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

        val baseSpec = project.toMultiPlaybackSpec(mixTracks)
            ?: return OfflineMixdownResult.WriteFailed

        val sessionEndMs = mixdownTimelineEndMs(tracks, selectedTrackIds)
        val startPositionMs =
            playbackStartPositionMsForTracks(
                scrubbedPlayheadMs = 0L,
                timelineVisibleDurationMs = sessionEndMs,
                tracks = mixTracks,
            )
        val spec =
            baseSpec.copy(
                startPositionMs = startPositionMs,
                sessionTimelineEndMs = sessionEndMs,
            )
        TransportFrameDiagnostics.logPlaybackArm(spec, transportStartFrame = -1L, transportFrame = -1L)

        return when (
            val result =
                audioController.renderOfflineMixdown(
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
