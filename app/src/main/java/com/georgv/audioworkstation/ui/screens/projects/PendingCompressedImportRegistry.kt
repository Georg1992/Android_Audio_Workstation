package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.CompressedAudioMetadata
import com.georgv.audioworkstation.core.audio.UriBackedAudioImportSource
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class PendingCompressedImport(
    val source: UriBackedAudioImportSource,
    val metadata: CompressedAudioMetadata,
    val suggestedTrackName: String?,
)

/**
 * Holds a compressed import waiting to start in a newly created project after navigation.
 * Consumed once when the destination [ProjectViewModel] binds.
 */
@Singleton
class PendingCompressedImportRegistry @Inject constructor() {
    private val pendingByProjectId = ConcurrentHashMap<String, PendingCompressedImport>()

    fun assign(
        projectId: String,
        pending: PendingCompressedImport,
    ) {
        pendingByProjectId[projectId] = pending
    }

    fun consume(projectId: String): PendingCompressedImport? = pendingByProjectId.remove(projectId)
}
