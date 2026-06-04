package com.georgv.audioworkstation.ui.screens.projects

import android.content.Context
import android.provider.OpenableColumns
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.AudioImportResult
import com.georgv.audioworkstation.core.audio.AudioImportSource
import com.georgv.audioworkstation.core.audio.AudioImportTarget
import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.core.audio.CompressedAudioMetadataReader
import com.georgv.audioworkstation.core.audio.Mp3ImportTiming
import com.georgv.audioworkstation.core.audio.MediaCodecAudioImporter
import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.core.audio.UriBackedAudioImportSource
import com.georgv.audioworkstation.core.audio.WavAudioImporter
import com.georgv.audioworkstation.core.audio.detectImportFormat
import com.georgv.audioworkstation.core.audio.DetectedImportFormat
import com.georgv.audioworkstation.core.audio.AudioImportProgressUpdate
import com.georgv.audioworkstation.core.validation.NameValidationResult
import com.georgv.audioworkstation.core.validation.validateName
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

sealed class ProjectAudioImportOutcome {
    data object StorageUnavailable : ProjectAudioImportOutcome()
    data class ImportRejected(val failure: AudioImportResult.Failure) : ProjectAudioImportOutcome()
    data class ReadyToPersist(val importedTrack: TrackEntity) : ProjectAudioImportOutcome()
    data class ImportStarted(val importingTrack: TrackEntity, val session: BackgroundImportSession) :
        ProjectAudioImportOutcome()
}

data class BackgroundImportSession(
    val source: UriBackedAudioImportSource,
    val destinationPath: String,
    val target: AudioImportTarget,
    val estimatedDurationMs: Long,
)

/**
 * Orchestrates project audio import:
 * - WAV: synchronous import, immediate READY
 * - Compressed: metadata read → persist IMPORTING → background MediaCodec decode → READY
 *
 * Exact waveform extraction is not part of import; it runs post-READY via
 * [ProjectWaveformPeakCoordinator].
 */
class ProjectAudioImportCoordinator @Inject constructor(
    private val repo: ProjectRepository,
    private val wavAudioImporter: WavAudioImporter,
    private val mediaCodecAudioImporter: MediaCodecAudioImporter,
    private val audioFilePathProvider: AudioFilePathProvider,
    @ApplicationContext private val context: Context,
) {

    suspend fun prepare(
        projectId: String,
        project: ProjectEntity,
        visibleTrackCount: Int,
        source: AudioImportSource,
        suggestedName: String?,
    ): ProjectAudioImportOutcome {
        val pendingTrack =
            repo.appendTrackToProject(projectId, "Take ${visibleTrackCount + 1}")
        val destinationPath =
            audioFilePathProvider.trackOutputPath(projectId, pendingTrack.id)
                ?: return ProjectAudioImportOutcome.StorageUnavailable

        val target =
            AudioImportTarget(
                sampleRate = project.sampleRate,
                fileBitDepth = project.fileBitDepth,
                channelMode = pendingTrack.channelMode,
            )

        return when (detectImportFormat(source)) {
            DetectedImportFormat.PcmWav -> runSynchronousWavImport(source, pendingTrack, destinationPath, target, suggestedName, visibleTrackCount)
            DetectedImportFormat.CompressedAudio ->
                prepareCompressedImport(
                    source = source,
                    pendingTrack = pendingTrack,
                    destinationPath = destinationPath,
                    target = target,
                    suggestedName = suggestedName,
                    visibleTrackCount = visibleTrackCount,
                )
            DetectedImportFormat.Unknown ->
                ProjectAudioImportOutcome.ImportRejected(AudioImportResult.Failure.UnsupportedEncoding)
        }
    }

    suspend fun executeBackgroundImport(
        importingTrack: TrackEntity,
        session: BackgroundImportSession,
        onProgress: (AudioImportProgressUpdate) -> Unit,
    ): ProjectAudioImportOutcome =
        when (
            val result =
                mediaCodecAudioImporter.importWithProgress(
                    source = session.source,
                    destinationPath = session.destinationPath,
                    target = session.target,
                    estimatedDurationMs = session.estimatedDurationMs,
                    onProgress = onProgress,
                )
        ) {
            is AudioImportResult.Success -> {
                onProgress(
                    AudioImportProgressUpdate(
                        fraction = 1f,
                        decodedDurationMs = result.durationMs,
                    ),
                )
                ProjectAudioImportOutcome.ReadyToPersist(
                    importingTrack.copy(
                        wavFilePath = session.destinationPath,
                        duration = result.durationMs,
                        channelMode = result.channelMode,
                        channelCount = result.channelCount,
                        importStatus = TrackImportStatus.READY,
                    ),
                )
            }
            is AudioImportResult.Failure -> {
                File(session.destinationPath).delete()
                ProjectAudioImportOutcome.ImportRejected(result)
            }
        }

    private suspend fun runSynchronousWavImport(
        source: AudioImportSource,
        pendingTrack: TrackEntity,
        destinationPath: String,
        target: AudioImportTarget,
        suggestedName: String?,
        visibleTrackCount: Int,
    ): ProjectAudioImportOutcome =
        when (val result = wavAudioImporter.import(source, destinationPath, target)) {
            is AudioImportResult.Success ->
                ProjectAudioImportOutcome.ReadyToPersist(
                    buildImportedTrack(
                        pendingTrack = pendingTrack,
                        destinationPath = destinationPath,
                        result = result,
                        suggestedName = suggestedName,
                        visibleTrackCount = visibleTrackCount,
                        importStatus = TrackImportStatus.READY,
                    ),
                )
            is AudioImportResult.Failure ->
                ProjectAudioImportOutcome.ImportRejected(result)
        }

    private fun prepareCompressedImport(
        source: AudioImportSource,
        pendingTrack: TrackEntity,
        destinationPath: String,
        target: AudioImportTarget,
        suggestedName: String?,
        visibleTrackCount: Int,
    ): ProjectAudioImportOutcome {
        val uriSource =
            source as? UriBackedAudioImportSource
                ?: run {
                    Mp3ImportTiming.beginSession("compressed_import")
                    Mp3ImportTiming.recordFailure(
                        stage = "source_not_uri_backed",
                        error = null,
                        partialWavDeleted = false,
                    )
                    Mp3ImportTiming.endSession("rejected")
                    return ProjectAudioImportOutcome.ImportRejected(
                        AudioImportResult.Failure.UnsupportedEncoding,
                    )
                }

        Mp3ImportTiming.beginSession("compressed_import uri=${uriSource.uri}")
        Mp3ImportTiming.startStage("metadata_read")
        val metadata =
            CompressedAudioMetadataReader.read(context, uriSource.uri)
        Mp3ImportTiming.stopStage("metadata_read")
        if (metadata == null) {
            Mp3ImportTiming.recordFailure(stage = "metadata_read", error = null, partialWavDeleted = false)
            Mp3ImportTiming.endSession("metadata_failed")
            return ProjectAudioImportOutcome.ImportRejected(
                AudioImportResult.Failure.FileNotReadable,
            )
        }
        if (metadata.channelCount !in 1..2) {
            Mp3ImportTiming.recordFailure(
                stage = "metadata_channel_count",
                error = null,
                partialWavDeleted = false,
            )
            Mp3ImportTiming.endSession("unsupported_channels")
            return ProjectAudioImportOutcome.ImportRejected(
                AudioImportResult.Failure.UnsupportedChannelCount,
            )
        }
        val estimatedDurationMs = metadata.durationMs.coerceAtLeast(MIN_IMPORT_CLIP_DURATION_MS)
        val estimatedFrameCount =
            if (metadata.sampleRate > 0 && estimatedDurationMs > 0L) {
                (estimatedDurationMs * metadata.sampleRate) / MS_PER_SECOND
            } else {
                0L
            }
        val resamplingEnabled = metadata.sampleRate != target.sampleRate
        Mp3ImportTiming.setMetadata(
            sourceDisplayName = resolveSourceDisplayName(uriSource),
            sourceUriScheme = uriSource.uri.scheme,
            mimeType = metadata.mimeType.ifBlank { context.contentResolver.getType(uriSource.uri) },
            sourceSampleRate = metadata.sampleRate,
            targetSampleRate = target.sampleRate,
            resamplingEnabled = resamplingEnabled,
            channelCount = metadata.channelCount,
            durationMs = estimatedDurationMs,
            estimatedFrameCount = estimatedFrameCount,
        )
        val importingTrack =
            buildImportedTrack(
                pendingTrack = pendingTrack,
                destinationPath = destinationPath,
                result =
                    AudioImportResult.Success(
                        durationMs = estimatedDurationMs,
                        channelMode = if (metadata.channelCount == 1) ChannelMode.MONO else ChannelMode.STEREO,
                        channelCount = metadata.channelCount,
                    ),
                suggestedName = suggestedName,
                visibleTrackCount = visibleTrackCount,
                importStatus = TrackImportStatus.IMPORTING,
            )
        return ProjectAudioImportOutcome.ImportStarted(
            importingTrack = importingTrack,
            session =
                BackgroundImportSession(
                    source = uriSource,
                    destinationPath = destinationPath,
                    target = target,
                    estimatedDurationMs = estimatedDurationMs,
                ),
        )
    }

    private fun resolveSourceDisplayName(source: UriBackedAudioImportSource): String? =
        try {
            source.contentResolver
                .query(
                    source.uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else {
                        null
                    }
                } ?: source.uri.lastPathSegment
        } catch (_: Exception) {
            source.uri.lastPathSegment
        }

    private fun buildImportedTrack(
        pendingTrack: TrackEntity,
        destinationPath: String,
        result: AudioImportResult.Success,
        suggestedName: String?,
        visibleTrackCount: Int,
        importStatus: TrackImportStatus,
    ): TrackEntity {
        val importedName =
            suggestedName
                ?.let { validateName(it) as? NameValidationResult.Valid }
                ?.normalized
                ?: "Take ${visibleTrackCount + 1} (imported)"
        return pendingTrack.copy(
            name = importedName,
            wavFilePath = destinationPath,
            duration = result.durationMs,
            channelMode = result.channelMode,
            channelCount = result.channelCount,
            isImported = true,
            importStatus = importStatus,
        )
    }

    private companion object {
        const val MS_PER_SECOND = 1_000L
        const val MIN_IMPORT_CLIP_DURATION_MS = 1_000L
    }
}
