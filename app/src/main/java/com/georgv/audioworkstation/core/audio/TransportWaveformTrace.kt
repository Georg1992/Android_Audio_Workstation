package com.georgv.audioworkstation.core.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The latest slice of audio the transport line draws.
 * Players publish the samples they are already sending out.
 */
object TransportWaveformTrace {
    const val PointCount = 64
    const val BytesPerSample = 2

    private const val FullScale = 32768f
    private const val LowByte = 0xff

    private val silent = FloatArray(PointCount)
    private val published = MutableStateFlow(FloatArray(PointCount))
    val samples: StateFlow<FloatArray> = published.asStateFlow()

    fun rest(): FloatArray = silent

    fun publish(points: FloatArray) {
        check(points.size == PointCount)
        published.value = points.copyOf()
    }

    fun clear() {
        published.value = FloatArray(PointCount)
    }

    fun pcm16(bytes: ByteArray, offset: Int, length: Int, channels: Int): FloatArray {
        check(channels > 0)
        val frameBytes = channels * BytesPerSample
        check(length >= frameBytes)
        check(offset >= 0 && offset + length <= bytes.size)
        val frames = length / frameBytes
        val points = FloatArray(PointCount)
        var index = 0
        while (index < PointCount) {
            val frame = (index.toLong() * frames / PointCount).toInt()
            val at = offset + frame * frameBytes
            var mixed = 0
            var channel = 0
            while (channel < channels) {
                val sampleAt = at + channel * BytesPerSample
                val unsigned = (bytes[sampleAt].toInt() and LowByte) or (bytes[sampleAt + 1].toInt() shl 8)
                mixed += unsigned.toShort().toInt()
                channel += 1
            }
            points[index] = mixed.toFloat() / (channels * FullScale)
            index += 1
        }
        return points
    }
}
