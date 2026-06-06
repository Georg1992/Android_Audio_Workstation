package com.georgv.audioworkstation.ui.screens.projects

import android.content.Context
import android.provider.OpenableColumns
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.AudioImportProgressUpdate
import com.georgv.audioworkstation.core.audio.AudioImportResult
import com.georgv.audioworkstation.core.audio.AudioImportSource
import com.georgv.audioworkstation.core.audio.AudioImportTarget
import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.core.audio.CompressedAudioMetadata
import com.georgv.audioworkstation.core.audio.CompressedAudioMetadataReader
import com.georgv.audioworkstation.core.audio.DetectedImportFormat
import com.georgv.audioworkstation.core.audio.DelegatingAudioImporter
import com.georgv.audioworkstation.core.audio.Mp3ImportTiming
import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.core.audio.UriBackedAudioImportSource
import com.georgv.audioworkstation.core.audio.detectImportFormat
import com.georgv.audioworkstation.core.audio.isSupportedProjectSampleRate
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withIo
import com.georgv.audioworkstation.core.validation.NameValidationResult
import com.georgv.audioworkstation.core.validation.validateName
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

sealed class ProjectAudioImportOutcome {
    data object StorageUnavailable : ProjectAudioImportOutcome()

    data class ImportRejected(val failure: AudioImportResult.Failure) : ProjectAudioImportOutcome()

    data class ReadyToPersist(val importedTrack: TrackEntity) : ProjectAudioImportOutcome()

    data class ImportStarted(val importingTrack: TrackEntity, val session: BackgroundImportSession) :
        ProjectAudioImportOutcome()

    data class SampleRateMismatchRequired(
        val sourceSampleRateHz: Int,
        val projectSampleRateHz: Int,
        val pending: PendingCompressedImport,
    ) : ProjectAudioImportOutcome()
}

sealed class CreateProjectForImportOutcome {
    data class Success(val project: ProjectEntity) : CreateProjectForImportOutcome()

    data object Failed : CreateProjectForImportOutcome()

    data object UnsupportedSampleRate : CreateProjectForImportOutcome()
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
 * - Compressed: metadata read → (sample-rate prompt if needed) → persist IMPORTING → decode → READY
 *
 * Exact waveform extraction is not part of import; it runs post-READY via
 * [ProjectWaveformPeakCoordinator].
 */
class ProjectAudioImportCoordinator @Inject constructor(
    private val repo: ProjectRepository,
    private val audioImporter: DelegatingAudioImporter,
    private val audioFilePathProvider: AudioFilePathProvider,
    private val dispatchers: AppDispatchers,
    @ApplicationContext private val context: Context,
) {

    suspend fun prepare(
        projectId: String,
        project: ProjectEntity,
        visibleTrackCount: Int,
        source: AudioImportSource,
        suggestedName: String?,
    ): ProjectAudioImportOutcome =
        withIo(dispatchers, "import prepare") {
            when (detectImportFormat(source)) {
                DetectedImportFormat.PcmWav ->
                    prepareWavImport(projectId, project, visibleTrackCount, source, suggestedName)
                DetectedImportFormat.CompressedAudio ->
                    prepareCompressedImport(
                        projectId = projectId,
                        project = project,
                        visibleTrackCount = visibleTrackCount,
                        source = source,
                        suggestedName = suggestedName,
                    )
                DetectedImportFormat.Unknown ->
                    ProjectAudioImportOutcome.ImportRejected(AudioImportResult.Failure.UnsupportedEncoding)
            }
        }

    suspend fun startCompressedImportFromPending(
        projectId: String,
        project: ProjectEntity,
        visibleTrackCount: Int,
        pending: PendingCompressedImport,
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

        return buildCompressedImportStarted(
            uriSource = pending.source,
            metadata = pending.metadata,
            pendingTrack = pendingTrack,
            destinationPath = destinationPath,
            target = target,
            suggestedName = pending.suggestedTrackName,
            visibleTrackCount = visibleTrackCount,
            beginTimingSession = false,
        )
    }

    suspend fun createProjectForPendingImport(pending: PendingCompressedImport): CreateProjectForImportOutcome {
        val sourceSampleRateHz = pending.metadata.sampleRate
        if (!isSupportedProjectSampleRate(sourceSampleRateHz)) {
            return CreateProjectForImportOutcome.UnsupportedSampleRate
        }
        val projectName = deriveProjectNameFromImport(pending.suggestedTrackName)
        val projectId = UUID.randomUUID().toString()
        return try {
            val project =
                ProjectEntity(
                    id = projectId,
                    name = projectName,
                    sampleRate = sourceSampleRateHz,
                )
            repo.upsertProject(project)
            CreateProjectForImportOutcome.Success(project)
        } catch (_: Exception) {
            CreateProjectForImportOutcome.Failed
        }
    }

    suspend fun executeBackgroundImport(
        importingTrack: TrackEntity,
        session: BackgroundImportSession,
        onProgress: (AudioImportProgressUpdate) -> Unit,
    ): ProjectAudioImportOutcome =
        when (
            val result =
                audioImporter.importWithProgress(
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

    private suspend fun prepareWavImport(
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

        return runSynchronousWavImport(
            source = source,
            pendingTrack = pendingTrack,
            destinationPath = destinationPath,
            target = target,
            suggestedName = suggestedName,
            visibleTrackCount = visibleTrackCount,
        )
    }

    private suspend fun prepareCompressedImport(
        projectId: String,
        project: ProjectEntity,
        visibleTrackCount: Int,
        source: AudioImportSource,
        suggestedName: String?,
    ): ProjectAudioImportOutcome {
        val uriSource = source as? UriBackedAudioImportSource ?: return rejectNonUriCompressedImport()
        Mp3ImportTiming.beginSession("compressed_import uri=${uriSource.uri}")
        Mp3ImportTiming.startStage("metadata_read")
        val metadata = CompressedAudioMetadataReader.read(context, uriSource.uri)
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

        val projectSampleRateHz = project.sampleRate
        val mismatchDetected = metadata.sampleRate != projectSampleRateHz
        logCompressedMetadata(uriSource, metadata, projectSampleRateHz, mismatchDetected)

        if (mismatchDetected) {
            return ProjectAudioImportOutcome.SampleRateMismatchRequired(
                sourceSampleRateHz = metadata.sampleRate,
                projectSampleRateHz = projectSampleRateHz,
                pending =
                    PendingCompressedImport(
                        source = uriSource,
                        metadata = metadata,
                        suggestedTrackName = suggestedName,
                    ),
            )
        }

        val pendingTrack =
            repo.appendTrackToProject(projectId, "Take ${visibleTrackCount + 1}")
        val destinationPath =
            audioFilePathProvider.trackOutputPath(projectId, pendingTrack.id)
                ?: run {
                    Mp3ImportTiming.endSession("storage_unavailable")
                    return ProjectAudioImportOutcome.StorageUnavailable
                }

        val target =
            AudioImportTarget(
                sampleRate = project.sampleRate,
                fileBitDepth = project.fileBitDepth,
                channelMode = pendingTrack.channelMode,
            )

        return buildCompressedImportStarted(
            uriSource = uriSource,
            metadata = metadata,
            pendingTrack = pendingTrack,
            destinationPath = destinationPath,
            target = target,
            suggestedName = suggestedName,
            visibleTrackCount = visibleTrackCount,
            beginTimingSession = false,
        )
    }

    private suspend fun runSynchronousWavImport(
        source: AudioImportSource,
        pendingTrack: TrackEntity,
        destinationPath: String,
        target: AudioImportTarget,
        suggestedName: String?,
        visibleTrackCount: Int,
    ): ProjectAudioImportOutcome =
        when (val result = audioImporter.import(source, destinationPath, target)) {
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

    private fun buildCompressedImportStarted(
        uriSource: UriBackedAudioImportSource,
        metadata: CompressedAudioMetadata,
        pendingTrack: TrackEntity,
        destinationPath: String,
        target: AudioImportTarget,
        suggestedName: String?,
        visibleTrackCount: Int,
        beginTimingSession: Boolean,
    ): ProjectAudioImportOutcome {
        if (beginTimingSession) {
            Mp3ImportTiming.beginSession("compressed_import uri=${uriSource.uri}")
        }
        val estimatedDurationMs = metadata.durationMs.coerceAtLeast(MIN_IMPORT_CLIP_DURATION_MS)
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

    private fun logCompressedMetadata(
        uriSource: UriBackedAudioImportSource,
        metadata: CompressedAudioMetadata,
        projectSampleRateHz: Int,
        mismatchDetected: Boolean,
    ) {
        val estimatedDurationMs = metadata.durationMs.coerceAtLeast(MIN_IMPORT_CLIP_DURATION_MS)
        val estimatedFrameCount =
            if (metadata.sampleRate > 0 && estimatedDurationMs > 0L) {
                (estimatedDurationMs * metadata.sampleRate) / MS_PER_SECOND
            } else {
                0L
            }
        Mp3ImportTiming.setMetadata(
            sourceDisplayName = resolveSourceDisplayName(uriSource),
            sourceUriScheme = uriSource.uri.scheme,
            mimeType = metadata.mimeType.ifBlank { context.contentResolver.getType(uriSource.uri) },
            sourceSampleRate = metadata.sampleRate,
            targetSampleRate = projectSampleRateHz,
            resamplingEnabled = mismatchDetected,
            mismatchDetected = mismatchDetected,
            channelCount = metadata.channelCount,
            durationMs = estimatedDurationMs,
            estimatedFrameCount = estimatedFrameCount,
        )
    }

    private fun rejectNonUriCompressedImport(): ProjectAudioImportOutcome {
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

    private fun deriveProjectNameFromImport(suggestedName: String?): String {
        val stripped =
            suggestedName
                ?.substringBeforeLast('.')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        return when (val validation = validateName(stripped ?: DEFAULT_IMPORTED_PROJECT_NAME)) {
            is NameValidationResult.Valid -> validation.normalized
            is NameValidationResult.Invalid -> DEFAULT_IMPORTED_PROJECT_NAME
        }
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

    internal companion object {
        const val DEFAULT_IMPORTED_PROJECT_NAME = "Imported"
        const val MS_PER_SECOND = 1_000L
        const val MIN_IMPORT_CLIP_DURATION_MS = 1_000L

        fun shouldPromptSampleRateMismatch(
            sourceSampleRateHz: Int,
            projectSampleRateHz: Int,
        ): Boolean = sourceSampleRateHz != projectSampleRateHz
    }
}
