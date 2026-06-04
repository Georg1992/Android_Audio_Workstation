package com.georgv.audioworkstation.core.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams 16-bit interleaved PCM into a canonical WAV file and patches the header on [close].
 */
internal class StreamingPcmWavWriter(
    file: File,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitsPerSample: Int = 16,
) : Closeable {
    private val output: RandomAccessFile
    private var dataBytesWritten = 0L
    private val pcmWriteBuffer = ByteArray(PCM_WRITE_BUFFER_BYTES)

    init {
        file.parentFile?.mkdirs()
        output = RandomAccessFile(file, "rw")
        output.setLength(0)
        output.write(
            buildPlaceholderHeader(
                numChannels = channelCount,
                sampleRate = sampleRate,
                bitsPerSample = bitsPerSample,
            ),
        )
    }

    private fun buildPlaceholderHeader(
        numChannels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
    ): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        WavHeaderWriter.writeHeader(
            output = stream,
            numChannels = numChannels,
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            dataSizeBytes = 0,
        )
        return stream.toByteArray()
    }

    fun writePcmInt16(samples: ShortArray, sampleCount: Int) {
        if (sampleCount <= 0) return
        var sampleOffset = 0
        val maxSamplesPerChunk = pcmWriteBuffer.size / BYTES_PER_SAMPLE
        while (sampleOffset < sampleCount) {
            val chunkSamples = minOf(maxSamplesPerChunk, sampleCount - sampleOffset)
            val byteCount = chunkSamples * BYTES_PER_SAMPLE
            ByteBuffer.wrap(pcmWriteBuffer, 0, byteCount)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .put(samples, sampleOffset, chunkSamples)
            output.write(pcmWriteBuffer, 0, byteCount)
            sampleOffset += chunkSamples
        }
        dataBytesWritten += sampleCount.toLong() * BYTES_PER_SAMPLE
    }

    fun writePcm16FromByteBuffer(buffer: ByteBuffer, offset: Int, sizeBytes: Int) {
        if (sizeBytes <= 0) return
        val slice = buffer.duplicate()
        slice.order(ByteOrder.LITTLE_ENDIAN)
        slice.position(offset)
        slice.limit(offset + sizeBytes)

        var remaining = sizeBytes
        while (remaining > 0) {
            val chunkSize = minOf(remaining, pcmWriteBuffer.size)
            slice.get(pcmWriteBuffer, 0, chunkSize)
            output.write(pcmWriteBuffer, 0, chunkSize)
            remaining -= chunkSize
        }
        dataBytesWritten += sizeBytes.toLong()
    }

    fun writtenFrameCount(): Long =
        if (channelCount == 0) {
            0L
        } else {
            dataBytesWritten / (channelCount.toLong() * 2L)
        }

    override fun close() {
        Mp3ImportTiming.runStage("wav_header_patch") {
            WavHeaderWriter.patchDataSize(
                file = output,
                dataSizeBytes = dataBytesWritten,
                numChannels = channelCount,
                sampleRate = sampleRate,
                bitsPerSample = bitsPerSample,
            )
            output.close()
        }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val PCM_WRITE_BUFFER_BYTES = 32 * 1024
    }
}
