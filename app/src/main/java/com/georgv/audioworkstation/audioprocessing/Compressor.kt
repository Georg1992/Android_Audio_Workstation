package com.georgv.audioworkstation.audioprocessing

class Compressor {



    fun compressAudio(input: ByteArray, threshold: Double, ratio: Double, knee: Double, attackTime: Double, releaseTime: Double, makeupGain: Double): ByteArray {
        val output = ByteArray(input.size)
        var gain = 1.0
        var currentSample: Double
        var currentSampleAbs: Double
        var gainReduction: Double = 0.0
        var attackCoeff = Math.exp(-1 / (attackTime * 44100))
        var releaseCoeff = Math.exp(-1 / (releaseTime * 44100))

        for (i in input.indices) {
            currentSample = input[i].toDouble() / 128.0
            currentSampleAbs = Math.abs(currentSample)

            if (currentSampleAbs > threshold) {
                gainReduction = (1 / ratio - 1) * (currentSampleAbs - threshold) / (1 - threshold)
                gainReduction = Math.max(0.0, gainReduction)

                if (knee > 0) {
                    var x = currentSampleAbs - threshold
                    var y = x / knee
                    y = Math.min(1.0, y)
                    gainReduction *= y * y
                }
                gain *= (1 - gainReduction)
            }

            if (currentSampleAbs > threshold) {
                if (currentSample > 0) {
                    output[i] = (currentSample * (1 + gain) * 128).toInt().toByte()
                } else {
                    output[i] = (currentSample * (1 - gain) * 128).toInt().toByte()
                }
            } else {
                output[i] = input[i]
            }

            if (currentSampleAbs > threshold) {
                gain = attackCoeff * gain + (1 - attackCoeff) * (1 - gainReduction)
            } else {
                gain = releaseCoeff * gain + (1 - releaseCoeff) * 1.0
            }
        }
        return output
    }
}