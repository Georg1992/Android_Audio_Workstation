package com.georgv.audioworkstation.online

import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.data.repository.ProjectRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed class ShareResult {
    data object NeedsLogin : ShareResult()

    data class Shared(val sharedProjectId: String) : ShareResult()
}

@Singleton
class ProjectShareCoordinator @Inject constructor(
    private val sessions: AccountSessionStore,
    private val api: ProjectShareApi,
    private val projects: ProjectRepository,
    private val pending: PendingProjectShare,
    private val dispatchers: AppDispatchers,
) {
    suspend fun share(localProjectId: String, title: String): ShareResult {
        val session = sessions.current() ?: return holdLogin(localProjectId, title)
        return try {
            ShareResult.Shared(upload(session, localProjectId, title))
        } catch (error: OnlineApiException) {
            if (error.status != HttpUnauthorized) throw error
            sessions.clear()
            holdLogin(localProjectId, title)
        }
    }

    suspend fun completePending(): String? {
        val held = pending.take() ?: return null
        val session = checkNotNull(sessions.current()) { "Sign-in did not produce a session." }
        return upload(session, held.localProjectId, held.title)
    }

    private suspend fun holdLogin(localProjectId: String, title: String): ShareResult.NeedsLogin {
        pending.hold(localProjectId, title)
        return ShareResult.NeedsLogin
    }

    private suspend fun upload(session: AccountSession, localProjectId: String, title: String): String =
        withContext(dispatchers.io) {
            projects.observeProject(localProjectId).first()
                ?: throw ShareException("Project is not on this phone.")
            val audio = audioFiles(localProjectId)
            val shared = api.createSharedProject(session.token, title)
            audio.forEach { track -> storeTrack(session.token, shared.id, track) }
            shared.id
        }

    private suspend fun audioFiles(localProjectId: String): List<TrackAudio> {
        val tracks = projects.observeTracks(localProjectId).first()
        val audio = mutableListOf<TrackAudio>()
        for (track in tracks) {
            val path = track.wavFilePath
            if (path.isBlank()) continue
            val file = File(path)
            if (!file.isFile) throw ShareException("Audio file is missing.")
            audio.add(TrackAudio(clientTrackId = track.id, file = file))
        }
        return audio
    }

    private suspend fun storeTrack(token: String, sharedProjectId: String, track: TrackAudio) {
        val hash = Sha256Hex.ofFile(track.file)
        val size = track.file.length()
        val key = api.requestStorageKey(
            token,
            sharedProjectId,
            StorageKeyRequest(clientTrackId = track.clientTrackId, contentHash = hash, size = size),
        )
        api.writeStoredFile(token, key, track.file)
        api.commitStoredFile(
            token,
            sharedProjectId,
            SharedFileCommit(
                clientTrackId = track.clientTrackId,
                contentHash = hash,
                storageKey = key,
                size = size,
            ),
        )
    }

    private data class TrackAudio(val clientTrackId: String, val file: File)
}
