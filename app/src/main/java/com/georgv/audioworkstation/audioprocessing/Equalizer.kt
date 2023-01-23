package com.georgv.audioworkstation.audioprocessing

import java.lang.Math.*
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.pow

class Equalizer : Effect() {

    override fun apply(filePath: String) {
        TODO("Not yet implemented")
    }

    fun parametricEqualizer(audioSamples: ByteArray, frequencyBands: Array<Pair<Double, Double>>, gains: DoubleArray) {
        val sampleRate = 44100
        val nyquist = sampleRate / 2.0
        val audioDouble = audioSamples.map { it.toDouble() / 128.0 }.toDoubleArray()
        val fftMag = DoubleArray(audioDouble.size / 2 + 1)
        val fftPhase = DoubleArray(audioDouble.size / 2 + 1)
        val fft1d = DoubleFFT_1D(audioDouble.size.toLong())
        fft1d.realForward(audioDouble)
        for (i in fftMag.indices) {
            fftMag[i] =
                kotlin.math.sqrt(audioDouble[i * 2] * audioDouble[i * 2] + audioDouble[i * 2 + 1] * audioDouble[i * 2 + 1])
            fftPhase[i] = kotlin.math.atan2(audioDouble[i * 2 + 1], audioDouble[i * 2])
        }
        for (i in frequencyBands.indices) {
            val startIndex = (frequencyBands[i].first / nyquist * fftMag.size).toInt()
            val endIndex = (frequencyBands[i].second / nyquist * fftMag.size).toInt()
            val gain = 10.0.pow(gains[i] / 20.0)

            for (j in startIndex..endIndex) {
                fftMag[j] *= gain
            }
        }
        for (i in fftMag.indices) {
            audioDouble[i * 2] = fftMag[i] * kotlin.math.cos(fftPhase[i])
            audioDouble[i * 2 + 1] = fftMag[i] * kotlin.math.sin(fftPhase[i])
        }
        fft1d.realInverse(audioDouble, true)
        //audioSamples = audioDouble.map { (it * 128).toByte() }.toByteArray
    }


}
