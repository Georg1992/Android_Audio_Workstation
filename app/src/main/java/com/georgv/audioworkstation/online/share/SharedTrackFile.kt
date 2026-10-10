package com.georgv.audioworkstation.online.share

import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import java.io.File
import java.security.MessageDigest

internal data class SharedTrackFile(
    val trackId: String,
    val file: File,
)

/** Finished local takes. A project that is not shared never reaches this list. */
internal fun tracksToShare(tracks: List<TrackEntity>): List<SharedTrackFile> {
    val files = ArrayList<SharedTrackFile>()
    for (track in tracks) {
        if (track.isRecording || track.importStatus != TrackImportStatus.READY) continue
        if (track.wavFilePath.isBlank()) continue
        val file = File(track.wavFilePath)
        if (!file.isFile) continue
        files += SharedTrackFile(track.id, file)
    }
    return files
}

/** Shared takes whose local file is gone. Files still on the phone are left alone. */
internal fun tracksToRestore(tracks: List<TrackEntity>): List<TrackEntity> {
    val missing = ArrayList<TrackEntity>()
    for (track in tracks) {
        val hash = track.contentHash
        if (hash.isNullOrBlank() || track.wavFilePath.isBlank()) continue
        if (!File(track.wavFilePath).isFile) missing += track
    }
    return missing
}

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
