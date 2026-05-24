package com.georgv.audioworkstation.core.audio

import java.io.InputStream

internal data class ParsedWavHeader(
    val audioFormat: Int,
    val numChannels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataSizeBytes: Int,
)

/**
 * Reads the RIFF + fmt chunks and locates the data chunk. Leaves [input] positioned right
 * after the data-chunk header so the caller can stream audio bytes directly.
 */
internal fun parseWavHeader(input: InputStream): ParsedWavHeader? {
    val riffHeader = input.readExactly(WavRiffHeaderSizeBytes) ?: return null
    if (!riffHeader.startsWith(WavTags.RIFF, offset = 0) || !riffHeader.startsWith(WavTags.WAVE, offset = 8)) {
        return null
    }
    var audioFormat = -1
    var numChannels = -1
    var sampleRate = -1
    var bitsPerSample = -1
    var foundFmt = false
    while (true) {
        val chunkHeader = input.readExactly(WavChunkHeaderSizeBytes) ?: return null
        val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
        val chunkSize = chunkHeader.readLittleEndianInt(4)
        when (chunkId) {
            "fmt " -> {
                val fmt = readFmtChunk(input, chunkSize) ?: return null
                audioFormat = fmt.audioFormat
                numChannels = fmt.numChannels
                sampleRate = fmt.sampleRate
                bitsPerSample = fmt.bitsPerSample
                foundFmt = true
            }
            "data" -> {
                if (!foundFmt) return null
                return ParsedWavHeader(
                    audioFormat = audioFormat,
                    numChannels = numChannels,
                    sampleRate = sampleRate,
                    bitsPerSample = bitsPerSample,
                    dataSizeBytes = chunkSize,
                )
            }
            else -> {
                if (input.skipExactly(chunkSize.toLong()) == null) return null
                if (chunkSize % 2 == 1 && input.skipExactly(1L) == null) return null
            }
        }
    }
}

private data class FmtChunkFields(
    val audioFormat: Int,
    val numChannels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
)

private fun readFmtChunk(input: InputStream, chunkSize: Int): FmtChunkFields? {
    if (chunkSize < WavFmtChunkMinSizeBytes) return null
    val fmt = input.readExactly(chunkSize) ?: return null
    return FmtChunkFields(
        audioFormat = fmt.readLittleEndianShort(0).toInt() and WavUnsigned16BitMask,
        numChannels = fmt.readLittleEndianShort(WavFmtChannelCountOffset).toInt() and WavUnsigned16BitMask,
        sampleRate = fmt.readLittleEndianInt(WavFmtSampleRateOffset),
        bitsPerSample = fmt.readLittleEndianShort(WavFmtBitsPerSampleOffset).toInt() and WavUnsigned16BitMask,
    )
}

private object WavTags {
    val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    val WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
}
