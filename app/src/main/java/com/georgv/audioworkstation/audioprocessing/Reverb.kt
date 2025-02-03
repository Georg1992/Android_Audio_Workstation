package com.georgv.audioworkstation.audioprocessing

import kotlin.math.sqrt


class Reverb(var delayInMilliSeconds: Int, var decayFactor: Float,
             var reverbPercent: Int, var feedbackFactor: Float) : Effect(){

    inner class Comb(combDelay: Float, combDecay: Float, private val feedback:Float) {
        private val delayInSamples =
            ((delayInMilliSeconds + combDelay) * (sampleRate / 1000)).toInt()
        private val decay = decayFactor + combDecay
        private val buffer = FloatArray(delayInSamples)
        private var bufferIndex = 0

        fun applyComb(audioInput:FloatArray): FloatArray {
            val combOutput = FloatArray(audioInput.size)
            for (i in audioInput.indices) {
                combOutput[i] = buffer[bufferIndex]
                buffer[bufferIndex] = audioInput[i] * decay + buffer[bufferIndex] * feedback
                bufferIndex = (bufferIndex + 1) % delayInSamples
            }
            return combOutput
        }
    }

    private val sampleRate = 44100
    private val combList = arrayOf(
        Comb(0.0f, 0.0f,feedbackFactor*1f),
        Comb( +11.73f, 0.1313f,feedbackFactor*0.4f),
        Comb( +16f, -0.2743f,feedbackFactor*0.8f),
        Comb( +7.97f, -0.31f,feedbackFactor*0.1f)
    )

    override fun apply(floatArray: FloatArray): FloatArray {
        return processReverb(floatArray)
    }

    private fun processReverb(floatAudio: FloatArray): FloatArray {
        if(reverbPercent == 0){
            return floatAudio
        }
        val mix = mixCombsWithDry(floatAudio)

        //Method calls for 2 All Pass Filters. Defined at the bottom
        val allPassFilterSamples1 = allPassFilter(mix)
        val allPassFilterSamples2 = allPassFilter(allPassFilterSamples1)


        //normalizeSamples(allPassFilterSamples2)

        val test = mix
        return allPassFilterSamples2
    }


    private fun mixedCombsAsFloat(audioInput: FloatArray):FloatArray{
        val mixedCombs = FloatArray(audioInput.size)
        for (comb in combList){
            val combOutput = comb.applyComb(audioInput)
            for(i in mixedCombs.indices){
                mixedCombs[i] += combOutput[i]
            }
        }
        return mixedCombs
    }


    private fun mixCombsWithDry(audioInput: FloatArray):FloatArray{
        val mixedCombs = mixedCombsAsFloat(audioInput)
        val mixedWithDry = FloatArray(mixedCombs.size)

        for (i in mixedCombs.indices) {
            val outputCombByte = mixedCombs[i]
            if(i < audioInput.size) {
                val dryMixedByte = audioInput[i] * (100 - reverbPercent) / 100
                mixedWithDry[i] = dryMixedByte + (outputCombByte * reverbPercent / 100)
            }

        }
        //normalizeSamples(mixedWithDry)
        return mixedWithDry
    }




    private fun allPassFilter(samples: FloatArray): FloatArray {
        val delaySamples =
            (89.27f * (sampleRate / 1000)).toInt() // Number of delay samples. Calculated from number of samples per millisecond
        val allPassFilterSamples = FloatArray(samples.size)
        val decayFactor = 0.131f

        //Applying algorithm for All Pass Filter
        for (i in samples.indices) {
            allPassFilterSamples[i] = samples[i]
            if (i - delaySamples >= 0) allPassFilterSamples[i] += -decayFactor * allPassFilterSamples[i - delaySamples]
            if (i + 20 - delaySamples >= 1) allPassFilterSamples[i] += decayFactor * allPassFilterSamples[i + 20 - delaySamples]
        }

//        The following code calculates the RMS value by summing the
//        squares of each sample and then taking the square
//        root of the mean. The max value is then used to
//        normalize each sample so that the largest sample
//        is set to 1.0. This ensures that the audio will not clip,
//        but quiet parts of audio will not be amplified too much.

        var value = allPassFilterSamples[0]
        var max = 0.0f
        for (i in samples.indices) {
            max += allPassFilterSamples[i] * allPassFilterSamples[i]
        }
        max = sqrt(max / samples.size)
        for (i in samples.indices) {
            val currentValue = allPassFilterSamples[i] / max
            value = (value + (currentValue - value)) / max
            allPassFilterSamples[i] = value
        }
        return allPassFilterSamples
    }




    override fun apply(byteArray: ByteArray): ByteArray {
        return byteArray
    }
}
