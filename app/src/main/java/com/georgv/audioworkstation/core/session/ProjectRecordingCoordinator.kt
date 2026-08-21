package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.core.audio.CapturePort
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.MultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.PreparedExistingTrackRecording
import com.georgv.audioworkstation.core.audio.RecordingPunchContext
import com.georgv.audioworkstation.core.audio.RecordingSpec
import com.georgv.audioworkstation.core.audio.RecordingStopSnapshot
import com.georgv.audioworkstation.core.audio.RecordingStopSnapshot.Companion.SessionPerceivedPlaybackOffsetUnset
import com.georgv.audioworkstation.core.audio.SessionRecordingPlacement
import com.georgv.audioworkstation.core.audio.WavPunchSplicer
import com.georgv.audioworkstation.core.audio.toRecordingSpec
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.core.diagnostics.QuickRecordDiagnostics
import com.georgv.audioworkstation.core.diagnostics.ThreadingDiagnostics
import com.georgv.audioworkstation.data.repository.ProjectRepository
import java.io.File
import javax.inject.Inject
import kotlin.math.max

/** Start path for a new take; the ViewModel applies Flows, DB upsert, and rollback on failures. */
sealed class RecordingStartOutcome {

    /** [CapturePort.startRecording] returned null — caller shows [com.georgv.audioworkstation.R.string.error_recording_failed_to_start]. */
    data object EngineStartFailed : RecordingStartOutcome()

    data class ReadyToPersistRecordingRow(
        val newTrack: TrackEntity,
        val punchContext: RecordingPunchContext? = null,
    ) : RecordingStartOutcome()
}

/**
 * Deterministic recording helpers: allocate a pending track, start the native recorder, build the
 * optimistic row. No [MutableStateFlow], no user messages, no DB writes.
 */
class ProjectRecordingCoordinator @Inject constructor(
    private val repo: ProjectRepository,
    private val capture: CapturePort,
    private val audioFilePathProvider: AudioFilePathProvider,
    private val wavPunchSplicer: WavPunchSplicer,
    private val dispatchers: AppDispatchers,
) {

    /**
     * Reserve the next logical take row without starting the recorder (fast path for optimistic UI).
     */
    suspend fun allocatePendingRecordingTrack(
        projectId: String,
        visibleTrackCount: Int,
        timelineStartOffsetMs: Long = 0L,
    ): TrackEntity =
        repo.appendTrackToProject(
            projectId = projectId,
            name = "Take ${visibleTrackCount + 1}",
        ).copy(
            timelineStartOffsetMs = timelineStartOffsetMs.coerceAtLeast(0L),
        )

    /**
     * Start native capture for a row already published to the recording session flows.
     * On success returns the row enriched with output path + recording timestamps.
     */
    suspend fun startEngineForAllocatedTrack(
        project: ProjectEntity,
        pendingTrack: TrackEntity,
        punchRecording: PreparedExistingTrackRecording? = null,
        overdubPlaybackSpec: MultiPlaybackSpec? = null,
    ): RecordingStartOutcome {
        val quickActive = QuickRecordDiagnostics.isActiveFor(project.id)
        val pathStartMs = android.os.SystemClock.uptimeMillis()
        if (quickActive) {
            QuickRecordDiagnostics.logStepStart("recording file/path preparation", project.id)
        }
        val pathPrep =
            withAudioIo(dispatchers, "recording file/path preparation") {
                val tempRecordingPath =
                    punchRecording?.let {
                        audioFilePathProvider.trackRecordingTempPath(project.id, pendingTrack.id)
                    }
                val finalWavPath =
                    punchRecording?.let {
                        audioFilePathProvider.trackOutputPath(project.id, pendingTrack.id)
                    }
                tempRecordingPath?.let { File(it).delete() }
                tempRecordingPath to finalWavPath
            }
        val tempRecordingPath = pathPrep.first
        val finalWavPath = pathPrep.second
        if (quickActive) {
            QuickRecordDiagnostics.logStepEnd(
                "recording file/path preparation",
                pathStartMs,
                project.id,
                "tempPath=$tempRecordingPath finalPath=$finalWavPath",
            )
        }

        val recordingSpec =
            punchRecording?.let { prepared ->
                project.toRecordingSpec(pendingTrack).copy(
                    timelineStartOffsetMs = prepared.recordingTransportStartMs,
                )
            } ?: project.toRecordingSpec(pendingTrack)

        val engineStartMs = android.os.SystemClock.uptimeMillis()
        if (quickActive) {
            QuickRecordDiagnostics.logStepStart("CapturePort.startRecording", project.id)
        }
        val outputPath =
            withAudioIo(dispatchers, "CapturePort.startRecording") {
                startNativeCapture(
                    project = project,
                    pendingTrack = pendingTrack,
                    recordingSpec = recordingSpec,
                    tempRecordingPath = tempRecordingPath,
                    overdubPlaybackSpec = overdubPlaybackSpec,
                )
            } ?: run {
                if (quickActive) {
                    QuickRecordDiagnostics.logStepEnd("CapturePort.startRecording", engineStartMs, project.id, "failed")
                }
                return RecordingStartOutcome.EngineStartFailed
            }
        if (quickActive) {
            QuickRecordDiagnostics.logStepEnd(
                "CapturePort.startRecording",
                engineStartMs,
                project.id,
                "outputPath=$outputPath",
            )
        }

        val persistedWavPath = punchRecording?.track?.wavFilePath?.takeIf { it.isNotBlank() } ?: outputPath
        val newTrack =
            pendingTrack.copy(
                wavFilePath = persistedWavPath,
                timeStampStart = System.currentTimeMillis(),
                isRecording = true,
            )
        val punchContext =
            punchRecording?.let { prepared ->
                RecordingPunchContext(
                    originalWavPath = prepared.track.wavFilePath,
                    tempRecordingPath = outputPath,
                    finalWavPath = finalWavPath ?: outputPath,
                    spliceStartInClipMs = prepared.spliceStartInClipMs,
                    sampleRateHz = project.sampleRate,
                    fileBitDepth = project.fileBitDepth,
                )
            }
        return RecordingStartOutcome.ReadyToPersistRecordingRow(
            newTrack = newTrack,
            punchContext = punchContext,
        )
    }

    private fun startNativeCapture(
        project: ProjectEntity,
        pendingTrack: TrackEntity,
        recordingSpec: RecordingSpec,
        tempRecordingPath: String?,
        overdubPlaybackSpec: MultiPlaybackSpec?,
    ): String? {
        ThreadingDiagnostics.logWorkBoundary("CapturePort.startRecording", phase = "beforeNativeCall")
        val resolvedOutputPath =
            tempRecordingPath
                ?: audioFilePathProvider.trackOutputPath(project.id, pendingTrack.id)
        val path =
            if (overdubPlaybackSpec != null) {
                val capturePath = resolvedOutputPath ?: return null
                capture.startOverdubRecordingSession(
                    playbackSpec = overdubPlaybackSpec,
                    recordingSpec = recordingSpec,
                    outputPath = capturePath,
                )
            } else {
                capture.startRecording(
                    recordingSpec,
                    outputPath = tempRecordingPath,
                )
            }
        ThreadingDiagnostics.logWorkBoundary("CapturePort.startRecording", phase = "afterNativeCall")
        return path
    }

    /**
     * Re-arm an existing track row for punch recording — clip timeline position stays unchanged.
     */
    fun prepareExistingTrackForRecording(
        track: TrackEntity,
        playheadPositionMs: Long,
    ): PreparedExistingTrackRecording {
        val spliceStartInClipMs =
            (playheadPositionMs - track.timelineStartOffsetMs).coerceAtLeast(0L)
        return PreparedExistingTrackRecording(
            track =
                track.copy(
                    timeStampStop = null,
                ),
            spliceStartInClipMs = spliceStartInClipMs,
            recordingTransportStartMs = playheadPositionMs.coerceAtLeast(0L),
        )
    }

    suspend fun beginRecording(
        projectId: String,
        project: ProjectEntity,
        visibleTrackCount: Int,
        timelineStartOffsetMs: Long = 0L,
        recordTargetTrack: TrackEntity? = null,
        playheadPositionMs: Long = timelineStartOffsetMs,
    ): RecordingStartOutcome {
        val punchRecording =
            recordTargetTrack?.let { target ->
                prepareExistingTrackForRecording(
                    track = target,
                    playheadPositionMs = playheadPositionMs,
                )
            }
        val pendingTrack =
            punchRecording?.track
                ?: allocatePendingRecordingTrack(
                    projectId = projectId,
                    visibleTrackCount = visibleTrackCount,
                    timelineStartOffsetMs = timelineStartOffsetMs,
                )
        return startEngineForAllocatedTrack(
            project = project,
            pendingTrack = pendingTrack,
            punchRecording = punchRecording,
        )
    }

    /**
     * Row to persist after a successful [CapturePort.stopRecording].
     * Punch recordings splice temp audio into the live track WAV on success.
     *
     * Capture placement: [RecordingStopSnapshot.firstSampleTransportPositionMs] is stored on
     * [TrackEntity.timelineStartOffsetMs] for visual alignment. Overdub playback scheduling uses
     * [TrackEntity.overdubPlaybackSyncOffsetMs] via [TrackEntity.playbackTimelineClipStartMs].
     */
    fun finalizeTrackAfterStop(
        currentTrack: TrackEntity,
        punchContext: RecordingPunchContext?,
        stopSnapshot: RecordingStopSnapshot = RecordingStopSnapshot(
            firstSampleTransportPositionMs = CapturePort.RecordingFirstSampleTransportUnset,
            capturedFrameCount = 0L,
            capturedDurationMs = 0L,
        ),
        overdubPlaybackStartMs: Long? = null,
    ): TrackEntity {
        val stopTimestamp = System.currentTimeMillis()
        if (punchContext == null) {
            val duration =
                if (stopSnapshot.capturedDurationMs > 0L) {
                    stopSnapshot.capturedDurationMs
                } else {
                    max(0L, stopTimestamp - currentTrack.timeStampStart)
                }
            val capturePlacementMs = stopSnapshot.firstSampleTransportPositionMs
            require(capturePlacementMs >= 0L) {
                "Recording stop missing capture placement transport"
            }
            val isOverdub = overdubPlaybackStartMs != null
            val syncOffsetMs =
                if (isOverdub &&
                    stopSnapshot.sessionPerceivedPlaybackOffsetMs >= 0L
                ) {
                    stopSnapshot.sessionPerceivedPlaybackOffsetMs
                } else {
                    TrackEntity.OverdubPlaybackSyncOffsetUnset
                }
            return currentTrack.copy(
                timelineStartOffsetMs = capturePlacementMs.coerceAtLeast(0L),
                overdubPlaybackSyncOffsetMs = syncOffsetMs,
                overdubBackingArmMs = overdubPlaybackStartMs?.coerceAtLeast(0L) ?: 0L,
                trimStartMs = currentTrack.trimStartMs.coerceAtLeast(0L),
                timeStampStop = stopTimestamp,
                duration = duration,
                isRecording = false,
            )
        }

        val spliceResult =
            wavPunchSplicer.splice(
                originalWavPath = punchContext.originalWavPath,
                tempRecordingWavPath = punchContext.tempRecordingPath,
                finalWavPath = punchContext.finalWavPath,
                spliceStartInClipMs = punchContext.spliceStartInClipMs,
                expectedSampleRateHz = punchContext.sampleRateHz,
                expectedBitDepth = punchContext.fileBitDepth,
            )
        return currentTrack.copy(
            wavFilePath = spliceResult.outputPath,
            timeStampStop = stopTimestamp,
            duration = spliceResult.durationMs,
            isRecording = false,
        )
    }

    /** @see finalizeTrackAfterStop */
    fun finalizedTrackAfterStop(
        currentTrack: TrackEntity,
        stopSnapshot: RecordingStopSnapshot = RecordingStopSnapshot(
            firstSampleTransportPositionMs = CapturePort.RecordingFirstSampleTransportUnset,
            capturedFrameCount = 0L,
            capturedDurationMs = 0L,
        ),
        overdubPlaybackStartMs: Long? = null,
    ): TrackEntity =
        finalizeTrackAfterStop(
            currentTrack,
            punchContext = null,
            stopSnapshot = stopSnapshot,
            overdubPlaybackStartMs = overdubPlaybackStartMs,
        )

    fun discardPunchRecordingTempFile(punchContext: RecordingPunchContext?) {
        val tempPath = punchContext?.tempRecordingPath ?: return
        File(tempPath).delete()
    }
}
