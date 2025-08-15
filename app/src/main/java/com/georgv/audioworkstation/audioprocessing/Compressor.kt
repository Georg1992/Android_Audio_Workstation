package com.georgv.audioworkstation.audioprocessing

import kotlin.math.abs
import kotlin.math.exp
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Compressor(val threshold: Float, val ratio: Float, val knee: Float, val attackTime: Float, val releaseTime: Float, val makeupGain: Float): Effect() {


    override fun apply(floatArray: FloatArray): FloatArray {
        return compressAudio(floatArray)
    }


    private fun compressAudio(input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        var gain = 1f
        var filteredGain = gain
        val attackCoeff = exp(-1.0 / (44100 * attackTime))
        val releaseCoeff = exp(-1.0 / (44100 * releaseTime))

        val limiterEnabled: Boolean = false

        for (i in input.indices) {
            val inputSample = input[i]
            val diff = abs(inputSample) - threshold
            val reduction = if (diff > 0) {
                diff * ratio
            } else {
                0f
            }
            gain = if (reduction > 0) {
                (1 / ratio).coerceAtLeast(1 - reduction / (knee + reduction))
            } else {
                (1 / ratio).coerceAtLeast(1 + diff / (knee - diff))
            }
            filteredGain = if ( gain > filteredGain) {
                (filteredGain * attackCoeff).toFloat() + ( gain * (1 - attackCoeff)).toFloat()
            } else {
                (filteredGain * releaseCoeff).toFloat() + (gain * (1 - releaseCoeff)).toFloat()
            }
            output[i] = if (makeupGain == 0f) inputSample * filteredGain else inputSample * filteredGain * makeupGain
            if (limiterEnabled) {
                output[i] = output[i].coerceAtMost(0.99f)
            }
        }

        return output
    }


    override fun apply(byteArray: ByteArray): ByteArray {
        val floats = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { shortBuffer ->
            val out = FloatArray(shortBuffer.remaining())
            var idx = 0
            while (shortBuffer.hasRemaining()) {
                out[idx++] = shortBuffer.get() / 32768.0f
            }
            out
        }
        val processed = apply(floats)
        val outShorts = ShortArray(processed.size)
        for (i in processed.indices) {
            val clamped = processed[i].coerceIn(-1.0f, 1.0f)
            outShorts[i] = (clamped * 32767.0f).toInt().toShort()
        }
        val outBytes = ByteArray(outShorts.size * 2)
        ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outShorts)
        return outBytes
    }
}