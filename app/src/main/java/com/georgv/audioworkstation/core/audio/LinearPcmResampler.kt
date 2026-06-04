package com.georgv.audioworkstation.core.audio

import kotlin.math.floor

/**
 * Linear-interpolation resampler for interleaved 16-bit PCM. Keeps a small sliding window so
 * decoding can stream without loading the full file into memory.
 */
internal class LinearPcmResampler(
    private val sourceRate: Int,
    private val targetRate: Int,
    private val channelCount: Int,
) {
    private val ratio = sourceRate.toDouble() / targetRate.toDouble()
    private var outputFrameIndex = 0L
    private var sourceBaseFrame = 0L
    private var lookahead = ShortArray(0)

    val isPassThrough: Boolean = sourceRate == targetRate

    fun resample(input: ShortArray, inputFrameCount: Int): ShortArray {
        require(inputFrameCount >= 0)
        if (isPassThrough) {
            return input.copyOf(inputFrameCount * channelCount)
        }
        if (inputFrameCount == 0) return ShortArray(0)

        appendInput(input, inputFrameCount)

        val availableFrames = lookahead.size / channelCount
        val estimatedOutputFrames =
            ((inputFrameCount.toDouble() * targetRate) / sourceRate).toInt().coerceAtLeast(0)
        val output = ShortArray(estimatedOutputFrames * channelCount)
        var outputIndex = 0

        while (true) {
            val sourcePosition = outputFrameIndex * ratio
            val sourceFrameIndex = floor(sourcePosition).toInt()
            val fraction = sourcePosition - sourceFrameIndex
            val localFrameIndex = sourceFrameIndex - sourceBaseFrame.toInt()
            if (localFrameIndex + 1 >= availableFrames) break

            var channel = 0
            while (channel < channelCount) {
                val firstIndex = localFrameIndex * channelCount + channel
                val secondIndex = (localFrameIndex + 1) * channelCount + channel
                val first = lookahead[firstIndex].toDouble()
                val second = lookahead[secondIndex].toDouble()
                val interpolated = first + (second - first) * fraction
                val clamped =
                    interpolated
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                if (outputIndex >= output.size) break
                output[outputIndex] = clamped
                outputIndex++
                channel++
            }
            outputFrameIndex++
        }

        trimLookahead(availableFrames)
        return if (outputIndex == output.size) output else output.copyOf(outputIndex)
    }

    private fun appendInput(input: ShortArray, inputFrameCount: Int) {
        val appendedSize = inputFrameCount * channelCount
        if (lookahead.isEmpty()) {
            lookahead = input.copyOf(appendedSize)
            return
        }
        val combined = ShortArray(lookahead.size + appendedSize)
        lookahead.copyInto(combined)
        input.copyInto(combined, destinationOffset = lookahead.size, endIndex = appendedSize)
        lookahead = combined
    }

    private fun trimLookahead(availableFrames: Int) {
        if (availableFrames <= 1) return

        val consumedSourceFrame = floor(outputFrameIndex * ratio).toInt()
        val consumedLocal = (consumedSourceFrame - sourceBaseFrame.toInt()).coerceAtLeast(0)
        val keepFromFrame = consumedLocal.coerceAtMost(availableFrames - 1)
        if (keepFromFrame <= 0) return

        sourceBaseFrame += keepFromFrame.toLong()
        lookahead = lookahead.copyOfRange(keepFromFrame * channelCount, lookahead.size)
    }
}
