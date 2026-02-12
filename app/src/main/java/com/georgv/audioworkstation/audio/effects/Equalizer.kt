package com.georgv.audioworkstation.audio.effects
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Equalizer(val band1:Int,val band2:Int,val band3:Int,val band4:Int,val band5:Int,val band6:Int) : Effect() {
    private var gains:Array<Int> = arrayOf(band1,band2,band3,band4,band5,band6)

    override fun apply(floatArray: FloatArray): FloatArray {
        return parametricEqualizer(floatArray)
    }

    private val frequencyBands = arrayOf(
        Pair(0, 200),
        Pair(200, 1000),
        Pair(1000, 4000),
        Pair(4000, 10000),
        Pair(10000, 16000),
        Pair(16000, 22000)
    )

    private fun getNextPowerOfTwo(num: Int): Int {
        return 2.0.pow(ceil(ln(num.toDouble()) / Math.log(2.0))).toInt()
    }

    private fun parametricEqualizer(audioSamples: FloatArray): FloatArray {
        val fftSize = getNextPowerOfTwo(audioSamples.size)
        val fft = FloatFFT_1D(fftSize.toLong())

        val complexBuffer = FloatArray(2 * fftSize)
        for (i in audioSamples.indices) {
            complexBuffer[2 * i] = audioSamples[i]
            complexBuffer[2 * i + 1] = 0f
        }

        fft.complexForward(complexBuffer)

        for (i in frequencyBands.indices) {
            val band = frequencyBands[i]
            val gain = 10.0.pow(gains[i].toFloat() / 20.0).toFloat()

            val startBin = (band.first * fftSize / 44100).toInt()
            val endBin = (band.second * fftSize / 44100).toInt()

            for (bin in startBin until endBin) {
                val re = complexBuffer[2 * bin]
                val im = complexBuffer[2 * bin + 1]

                val magnitude = sqrt(re * re + im * im.toDouble()).toFloat()
                val phase = atan2(im.toDouble(), re.toDouble()).toFloat()

                complexBuffer[2 * bin] = magnitude * gain * cos(phase.toDouble()).toFloat()
                complexBuffer[2 * bin + 1] = magnitude * gain * sin(phase.toDouble()).toFloat()
            }
        }

        fft.complexInverse(complexBuffer, true)

        val result = FloatArray(audioSamples.size)
        for (i in audioSamples.indices) {
            result[i] = complexBuffer[2 * i]
        }

        return result
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
