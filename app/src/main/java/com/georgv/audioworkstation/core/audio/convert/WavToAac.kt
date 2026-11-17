package com.georgv.audioworkstation.core.audio.convert

import com.georgv.audioworkstation.core.audio.readWavPcmFileInfo
import java.io.File
import java.io.RandomAccessFile

/**
 * Community mix converter. AAC-LC at 128 kbps per channel, in short pieces,
 * which is the AAC Apple Music streams.
 */
class WavToAac(private val codec: AacCodec) : AudioConverter {
    override fun convert(input: File): File {
        val info = readWavPcmFileInfo(input.absolutePath) ?: error("mix is not pcm")
        if (!info.isSupportedProjectPcm || info.dataSize <= 0L || info.dataSize > Int.MAX_VALUE) {
            error("mix is not pcm")
        }
        val pcm = pcm(input, info.dataOffset, info.dataSize.toInt())
        val pieceBytes = pieceBytes(info.sampleRateHz, info.blockAlign)
        val packets = ArrayList<ByteArray>()
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + pieceBytes, pcm.size)
            packets += codec.encode(pcm.copyOfRange(offset, end), info.sampleRateHz, info.channelCount)
            offset = end
        }
        val output = File.createTempFile("community-mix", ".aac")
        var written = false
        try {
            AacMixFile.write(output, info, pieceBytes, packets)
            written = true
            return output
        } finally {
            if (!written) output.delete()
        }
    }

    private fun pcm(file: File, dataOffset: Long, size: Int): ByteArray {
        val bytes = ByteArray(size)
        RandomAccessFile(file, "r").use { wav ->
            wav.seek(dataOffset)
            wav.readFully(bytes)
        }
        return bytes
    }

    private fun pieceBytes(sampleRateHz: Int, blockAlign: Int): Int {
        val frames = PieceMs * sampleRateHz / MsPerSecond
        return (frames * blockAlign).toInt().coerceAtLeast(blockAlign)
    }

    private companion object {
        const val PieceMs = 2_000L
        const val MsPerSecond = 1_000L
    }
}
