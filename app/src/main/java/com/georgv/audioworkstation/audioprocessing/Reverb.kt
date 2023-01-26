package com.georgv.audioworkstation.audioprocessing

import kotlin.math.abs

const val MAX_BUFFER_SIZE = 8e+6

class Reverb() : Effect() {

    private val sampleRate = AudioController.SAMPLE_RATE.toFloat()
    var delayInMilliSeconds: Float = 1000f
    var decayFactor: Float = 0.5f
    var mixPercent: Int = 50


    override fun apply(byteArray:ByteArray):ByteArray {
        return processReverb(byteArray)
    }


    private fun processReverb(byteArray: ByteArray): ByteArray {
        val audioBuffer: ByteArray = byteArray

        val frameSize = 4 //16 bit audio 2 channels
        val floatBufferSize = audioBuffer.size * frameSize

        val samples = AudioProcessor.toFloatArray(audioBuffer, floatBufferSize)
        //val final = AudioProcessor.toByteArray(samples,audioBuffer,byteArray.size)

        return audioBuffer
        //Adding the 4 Comb Filters
//        var outputComb = FloatArray(floatBufferSize)
//        var comb1 = combFilter(samples, floatBufferSize, delayInMilliSeconds, decayFactor)
//        var comb2 =combFilter(samples, floatBufferSize,
//            delayInMilliSeconds - 11.73f, decayFactor - 0.1313f)
//        var comb3 = combFilter(samples, floatBufferSize,
//            delayInMilliSeconds + 19.31f, decayFactor - 0.2743f)
//        var comb4 =  combFilter(samples, floatBufferSize,
//            delayInMilliSeconds - 7.97f, decayFactor - 0.31f)
//
//        for (i in 0 until floatBufferSize) {
//            outputComb[i] = comb1[i] + comb2[i] + comb3[i] + comb4[i]
//        }
//        comb1 = floatArrayOf()
//        comb2 = floatArrayOf()
//        comb3 = floatArrayOf()
//        comb4 = floatArrayOf()
//
//        var mixAudio = FloatArray(floatBufferSize)
//        for (i in 0 until floatBufferSize) {
//            mixAudio[i] = ((100 - mixPercent) * originalSamplesAsFloat[i]) + (outputComb[i] * mixPercent)
//        }
//        outputComb = floatArrayOf()
//        //Method calls for 2 All Pass Filters. Defined at the bottom
//        var allPassFilterSamples1 = allPassFilter(mixAudio, floatBufferSize)
//        mixAudio = floatArrayOf()
//        var allPassFilterSamples2 = allPassFilter(allPassFilterSamples1, floatBufferSize)
//        allPassFilterSamples1 = floatArrayOf()
//
//        val finalAudioSamples = ByteArray(floatBufferSize)
//        AudioProcessor.pack(allPassFilterSamples2, finalAudioSamples, audioBuffer.size * 2)
//        allPassFilterSamples2 = floatArrayOf()
//
}

    private fun combFilter(
        samples: FloatArray,
        samplesLength: Int,
        delayInMilliSeconds: Float,
        decayFactor: Float
    ): FloatArray {
        //Calculating delay in samples from the delay in Milliseconds. Calculated from number of samples per millisecond
        val delaySamples = (delayInMilliSeconds * (sampleRate / 1000)).toInt()

        //Applying algorithm for Comb Filter
        for (i in 0 until (samplesLength - delaySamples)) {
            samples[i + delaySamples] += samples[i] * decayFactor
        }
        return samples
    }


    private fun allPassFilter(samples: FloatArray, samplesLength: Int): FloatArray {
        val delaySamples =
            (89.27f * (sampleRate / 1000)).toInt() // Number of delay samples. Calculated from number of samples per millisecond
        val allPassFilterSamples = FloatArray(samplesLength)
        val decayFactor = 0.131f

        //Applying algorithm for All Pass Filter
        for (i in 0 until samplesLength) {
            allPassFilterSamples[i] = samples[i]
            if (i - delaySamples >= 0) allPassFilterSamples[i] += -decayFactor * allPassFilterSamples[i - delaySamples]
            if (i - delaySamples >= 1) allPassFilterSamples[i] += decayFactor * allPassFilterSamples[i + 20 - delaySamples]
        }

        //This is for smoothing out the samples and normalizing the audio. Without implementing this, the samples overflow causing clipping of audio
        var value = allPassFilterSamples[0]
        var max = 0.0f
        for (i in 0 until samplesLength) {
            if (abs(allPassFilterSamples[i]) > max) max = abs(
                allPassFilterSamples[i]
            )
        }
        for (i in allPassFilterSamples.indices) {
            val currentValue = allPassFilterSamples[i]
            value = (value + (currentValue - value)) / max
            allPassFilterSamples[i] = value
        }
        return samples
    }
}