package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.core.audio.WavPcmFileInfo
import com.georgv.audioworkstation.core.audio.isSupportedProjectSampleRate
import com.georgv.audioworkstation.core.audio.readWavPcmFileInfo
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.share.ShareObjectTransport
import com.georgv.audioworkstation.online.share.SharedProjects
import com.georgv.audioworkstation.online.share.SharedTake
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class SharedProjectPlace(
    private val projects: ProjectRepository,
    private val paths: AudioFilePathProvider,
    private val shared: SharedProjects,
    private val objects: ShareObjectTransport,
) {
    suspend fun place(projectId: String, title: String) = withContext(Dispatchers.IO) {
        if (projects.projectExists(projectId)) {
            addMissing(projectId)
            return@withContext
        }
        val stored = ArrayList<File>()
        try {
            writeProject(projectId, title, stored)
        } catch (cancel: CancellationException) {
            discard(projectId, stored)
            throw cancel
        } catch (error: Exception) {
            discard(projectId, stored)
            throw SharedPlaceException(error)
        }
    }

    private suspend fun writeProject(projectId: String, title: String, stored: MutableList<File>) {
        val takes = ArrayList<PlacedTake>()
        for (file in shared.files(projectId).sortedBy { it.trackId }) {
            val expectedRate = takes.firstOrNull()?.info?.sampleRateHz
            takes += storeTake(projectId, file, expectedRate, stored)
        }
        val created = projects.ensureProject(projectId, title)
        val rate = takes.firstOrNull()?.info?.sampleRateHz ?: created.sampleRate
        projects.upsertProject(created.copy(sampleRate = rate, remoteUrl = projectId))
        if (takes.isNotEmpty()) {
            projects.upsertTracks(takes.mapIndexed { index, take -> trackRow(projectId, index, take) })
        }
    }

    private suspend fun addMissing(projectId: String) {
        val project = projects.observeProject(projectId).first() ?: return
        val existing = projects.observeTracks(projectId).first()
        val known = existing.map { it.id }.toSet()
        val missing = shared.files(projectId).filter { it.trackId !in known }.sortedBy { it.trackId }
        val stored = ArrayList<File>()
        try {
            keepNewTakes(project, existing.size, missing, stored)
        } catch (cancel: CancellationException) {
            stored.forEach { file -> file.delete() }
            throw cancel
        } catch (error: Exception) {
            stored.forEach { file -> file.delete() }
            throw SharedPlaceException(error)
        }
    }

    private suspend fun keepNewTakes(
        project: ProjectEntity,
        position: Int,
        missing: List<SharedTake>,
        stored: MutableList<File>,
    ) {
        if (project.remoteUrl.isNullOrBlank()) {
            projects.upsertProject(project.copy(remoteUrl = project.id))
        }
        val takes = ArrayList<PlacedTake>()
        for (file in missing) {
            takes += storeTake(project.id, file, project.sampleRate, stored)
        }
        if (takes.isNotEmpty()) {
            projects.upsertTracks(
                takes.mapIndexed { index, take -> trackRow(project.id, position + index, take) },
            )
        }
    }

    private fun storeTake(
        projectId: String,
        file: SharedTake,
        sampleRateHz: Int?,
        stored: MutableList<File>,
    ): PlacedTake {
        val path = paths.trackOutputPath(projectId, file.trackId) ?: error("shared project has no storage")
        val destination = File(path)
        stored += destination
        objects.download(file.downloadUrl, destination)
        return PlacedTake(file, path, wavInfo(destination, sampleRateHz))
    }

    private fun wavInfo(destination: File, sampleRateHz: Int?): WavPcmFileInfo {
        val info = readWavPcmFileInfo(destination.absolutePath) ?: error("shared take is not pcm")
        val supported = info.isSupportedProjectPcm && isSupportedProjectSampleRate(info.sampleRateHz)
        val sameRate = sampleRateHz == null || sampleRateHz == info.sampleRateHz
        if (!supported || !sameRate) error("shared take is not pcm")
        return info
    }

    private fun trackRow(projectId: String, index: Int, take: PlacedTake): TrackEntity {
        val channels = take.info.channelCount
        return TrackEntity(
            id = take.file.trackId,
            projectId = projectId,
            name = "Take ${index + 1}",
            channelMode = if (channels == 1) ChannelMode.MONO else ChannelMode.STEREO,
            channelCount = channels,
            wavFilePath = take.path,
            duration = take.info.durationMs,
            position = index,
            contentHash = take.file.contentHash,
            syncStatus = SyncStatus.LOCAL,
        )
    }

    private suspend fun discard(projectId: String, stored: List<File>) {
        stored.forEach { file -> file.delete() }
        if (projects.projectExists(projectId)) projects.deleteProject(projectId)
    }
}

internal class SharedPlaceException(cause: Throwable) : Exception(cause)

private data class PlacedTake(
    val file: SharedTake,
    val path: String,
    val info: WavPcmFileInfo,
)
