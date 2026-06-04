package com.georgv.audioworkstation.core.audio

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import java.io.IOException

data class CompressedAudioMetadata(
    val durationMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val mimeType: String,
)

object CompressedAudioMetadataReader {

    fun read(context: Context, uri: Uri): CompressedAudioMetadata? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = CompressedMediaTrack.selectAudioTrackIndex(extractor) ?: return null
            extractor.selectTrack(trackIndex)
            readFromFormat(extractor.getTrackFormat(trackIndex))
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun readFromFormat(format: android.media.MediaFormat): CompressedAudioMetadata? {
        val trackInfo = CompressedMediaTrack.readTrackInfo(format) ?: return null
        return CompressedAudioMetadata(
            durationMs = CompressedMediaTrack.readDurationMs(format),
            sampleRate = trackInfo.sampleRate,
            channelCount = trackInfo.channelCount,
            mimeType = trackInfo.mimeType,
        )
    }
}
