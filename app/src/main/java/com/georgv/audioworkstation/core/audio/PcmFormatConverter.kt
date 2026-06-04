package com.georgv.audioworkstation.core.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts decoder PCM buffers into interleaved 16-bit samples for the internal WAV cache.
 */
internal object PcmFormatConverter {
    private const val PcmFloatFullScale = 32767f
    fun decodeOutputBuffer(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        encoding: Int,
        channelCount: Int,
    ): ShortArray {
        require(channelCount in 1..2) { "Only mono or stereo PCM is supported." }
        require(offset >= 0) { "PCM buffer offset must be non-negative." }
        require(sizeBytes >= 0) { "PCM buffer size must be non-negative." }
        if (sizeBytes == 0) return ShortArray(0)

        val slice = buffer.duplicate().order(ByteOrder.nativeOrder())
        slice.position(offset)
        slice.limit(offset + sizeBytes)

        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> decodePcm16(slice, sizeBytes)
            AudioFormat.ENCODING_PCM_FLOAT -> decodePcmFloat(slice, sizeBytes)
            AudioFormat.ENCODING_PCM_8BIT -> decodePcm8(slice, sizeBytes, channelCount)
            else -> throw UnsupportedPcmEncodingException(encoding)
        }
    }

    private fun decodePcm16(buffer: ByteBuffer, sizeBytes: Int): ShortArray {
        val sampleCount = sizeBytes / 2
        if (sampleCount == 0) return ShortArray(0)
        val output = ShortArray(sampleCount)
        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(output)
        return output
    }

    private fun decodePcmFloat(buffer: ByteBuffer, sizeBytes: Int): ShortArray {
        val sampleCount = sizeBytes / 4
        val output = ShortArray(sampleCount)
        var index = 0
        while (index < sampleCount) {
            val clamped = buffer.float.coerceIn(-1f, 1f)
            output[index] = (clamped * PcmFloatFullScale).toInt().toShort()
            index++
        }
        return output
    }

    private fun decodePcm8(buffer: ByteBuffer, sizeBytes: Int, channelCount: Int): ShortArray {
        val frameCount = sizeBytes / channelCount
        val output = ShortArray(frameCount * channelCount)
        var outIndex = 0
        repeat(frameCount) {
            repeat(channelCount) {
                val unsigned = buffer.get().toInt() and 0xFF
                val centered = unsigned - 128
                output[outIndex] = (centered shl 8).toShort()
                outIndex++
            }
        }
        return output
    }
}

internal class UnsupportedPcmEncodingException(encoding: Int) :
    IllegalArgumentException("Unsupported PCM encoding: $encoding")
