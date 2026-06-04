package com.georgv.audioworkstation.core.audio

import java.io.InputStream

internal enum class DetectedImportFormat {
    PcmWav,
    CompressedAudio,
    Unknown,
}

private val WavRiffTag = "RIFF".toByteArray(Charsets.US_ASCII)
private val WavWaveTag = "WAVE".toByteArray(Charsets.US_ASCII)
private val Mp3Id3Tag = "ID3".toByteArray(Charsets.US_ASCII)

private const val Mp3FrameSyncByte = 0xFF
private const val Mp3FrameSyncMask = 0xE0
private const val UnsignedByteMask = 0xFF

private val WavMimeTypes =
    setOf(
        "audio/wav",
        "audio/x-wav",
        "audio/vnd.wave",
        "audio/wave",
    )

private val CompressedAudioMimeTypes =
    setOf(
        "audio/mpeg",
        "audio/mp3",
        "audio/x-mpeg",
        "audio/mpeg3",
        "audio/mp4",
        "audio/aac",
        "audio/x-m4a",
        "audio/m4a",
    )

/**
 * Chooses the import path for a picked file. MIME type from [UriBackedAudioImportSource] is
 * preferred; otherwise the first bytes of the stream are inspected.
 */
internal fun detectImportFormat(source: AudioImportSource): DetectedImportFormat {
    if (source is UriBackedAudioImportSource) {
        detectFromMimeType(source.contentResolver.getType(source.uri))?.let { return it }
    }
    return detectFromStream(source)
}

private fun detectFromMimeType(mimeType: String?): DetectedImportFormat? {
    val normalized = mimeType?.lowercase()?.substringBefore(';')?.trim() ?: return null
    return when {
        normalized in WavMimeTypes -> DetectedImportFormat.PcmWav
        normalized in CompressedAudioMimeTypes -> DetectedImportFormat.CompressedAudio
        else -> null
    }
}

private fun detectFromStream(source: AudioImportSource): DetectedImportFormat {
    val stream = runCatching { source.open() }.getOrNull() ?: return DetectedImportFormat.Unknown
    return stream.use { input ->
        val header = input.readExactly(12) ?: return DetectedImportFormat.Unknown
        when {
            header.startsWith(WavRiffTag, offset = 0) && header.startsWith(WavWaveTag, offset = 8) ->
                DetectedImportFormat.PcmWav
            looksLikeCompressedAudio(header, input) -> DetectedImportFormat.CompressedAudio
            else -> DetectedImportFormat.Unknown
        }
    }
}

private fun looksLikeCompressedAudio(header: ByteArray, stream: InputStream): Boolean {
    if (header.startsWith(Mp3Id3Tag, offset = 0)) return true
    if (isMp3FrameSync(header[0], header[1])) return true
    val extra = stream.readExactly(4) ?: return false
    return isMp3FrameSync(extra[0], extra[1])
}

private fun isMp3FrameSync(first: Byte, second: Byte): Boolean {
    val firstUnsigned = first.toInt() and UnsignedByteMask
    val secondUnsigned = second.toInt() and UnsignedByteMask
    return firstUnsigned == Mp3FrameSyncByte && (secondUnsigned and Mp3FrameSyncMask) == Mp3FrameSyncMask
}
