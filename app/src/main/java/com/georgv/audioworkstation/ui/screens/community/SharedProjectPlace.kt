package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.core.audio.WavPcmFileInfo
import com.georgv.audioworkstation.core.audio.WavStreamHeader
import com.georgv.audioworkstation.core.audio.isSupportedProjectSampleRate
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.share.SharedProjects
import com.georgv.audioworkstation.online.share.SharedTake
import com.georgv.audioworkstation.ui.playback.MixPieces
import com.georgv.audioworkstation.ui.playback.MixSource
import com.georgv.audioworkstation.ui.playback.UrlMixSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class SharedProjectPlace(
    private val projects: ProjectRepository,
    private val shared: SharedProjects,
    private val headers: MixSource = UrlMixSource(),
) {
    suspend fun place(projectId: String, title: String) = withContext(Dispatchers.IO) {
        if (projects.projectExists(projectId)) {
            addMissing(projectId)
            return@withContext
        }
        try {
            writeProject(projectId, title)
        } catch (cancel: CancellationException) {
            discard(projectId)
            throw cancel
        } catch (error: Exception) {
            discard(projectId)
            throw SharedPlaceException(error)
        }
    }

    private suspend fun writeProject(projectId: String, title: String) {
        val takes = ArrayList<PlacedTake>()
        for (file in shared.files(projectId).sortedBy { it.trackId }) {
            val expectedRate = takes.firstOrNull()?.info?.sampleRateHz
            takes += PlacedTake(file, probe(file.downloadUrl, expectedRate))
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
        try {
            keepNewTakes(project, existing.size, missing)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            throw SharedPlaceException(error)
        }
    }

    private suspend fun keepNewTakes(project: ProjectEntity, position: Int, missing: List<SharedTake>) {
        if (project.remoteUrl.isNullOrBlank()) {
            projects.upsertProject(project.copy(remoteUrl = project.id))
        }
        val takes = ArrayList<PlacedTake>()
        for (file in missing) {
            takes += PlacedTake(file, probe(file.downloadUrl, project.sampleRate))
        }
        if (takes.isNotEmpty()) {
            projects.upsertTracks(
                takes.mapIndexed { index, take -> trackRow(project.id, position + index, take) },
            )
        }
    }

    private fun probe(url: String, sampleRateHz: Int?): WavPcmFileInfo {
        val info = headers.open(url, 0L, MixPieces.HeaderEnd).use { WavStreamHeader.read(it) }
            ?: error("shared take is not pcm")
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
            duration = take.info.durationMs,
            position = index,
            contentHash = take.file.contentHash,
            syncStatus = SyncStatus.LOCAL,
        )
    }

    private suspend fun discard(projectId: String) {
        if (projects.projectExists(projectId)) projects.deleteProject(projectId)
    }
}

internal class SharedPlaceException(cause: Throwable) : Exception(cause)

private data class PlacedTake(
    val file: SharedTake,
    val info: WavPcmFileInfo,
)
