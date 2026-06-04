package com.georgv.audioworkstation.core.audio

import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object WavHeaderWriter {
    private const val PCM_FORMAT = 1
    private const val BITS_PER_BYTE = 8
    private const val WAV_CANONICAL_FILE_HEADER_BYTES = 44

    fun writeHeader(
        output: OutputStream,
        numChannels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        dataSizeBytes: Int,
    ) {
        output.write(buildHeaderBytes(numChannels, sampleRate, bitsPerSample, dataSizeBytes))
    }

    fun patchDataSize(
        file: RandomAccessFile,
        dataSizeBytes: Long,
        numChannels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
    ) {
        require(dataSizeBytes <= Int.MAX_VALUE.toLong()) {
            "Imported audio exceeds supported WAV size."
        }
        val header = buildHeaderBytes(numChannels, sampleRate, bitsPerSample, dataSizeBytes.toInt())
        file.seek(0)
        file.write(header)
    }

    private fun buildHeaderBytes(
        numChannels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        dataSizeBytes: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(WAV_CANONICAL_FILE_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * numChannels * (bitsPerSample / BITS_PER_BYTE)
        val blockAlign = numChannels * (bitsPerSample / BITS_PER_BYTE)

        buffer.put(WavWriteTags.RIFF)
        buffer.putInt(WavCanonicalHeaderSizeBytes + dataSizeBytes)
        buffer.put(WavWriteTags.WAVE)
        buffer.put(WavWriteTags.FMT)
        buffer.putInt(WavFmtSubchunkPayloadSizeBytes)
        buffer.putShort(PCM_FORMAT.toShort())
        buffer.putShort(numChannels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put(WavWriteTags.DATA)
        buffer.putInt(dataSizeBytes)
        return buffer.array()
    }
}

private object WavWriteTags {
    val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    val WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
    val FMT = "fmt ".toByteArray(Charsets.US_ASCII)
    val DATA = "data".toByteArray(Charsets.US_ASCII)
}
