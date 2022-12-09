package com.georgv.audioworkstation.audioprocessing

import java.lang.StrictMath.pow
import java.nio.ByteBuffer
import java.nio.FloatBuffer


object AudioProcessor {

    fun toFloatArray(
        bytes: ByteArray,
        byteArrayLength: Int,
        floatBuffer:Int
    ): FloatArray {
        val samples = FloatArray(floatBuffer)
        val bitsPerSample: Int = 16
        val bytesPerSample: Int = 2
        val fullScale = fullScale(bitsPerSample)
        var i = 0
        var s = 0
        while (i < byteArrayLength) {
            var temp: Long = unpack16Bit(bytes, i)

            temp = extendSign(temp, bitsPerSample)
            val sample: Float = (temp / fullScale).toFloat()

            samples[s] = sample
            i += bytesPerSample
            s++
        }
        return samples
    }


    //	This method converts the byte data into a long
    //	When the data is stored in 16-bit encoding, the bytes need to be bit shifted into position, and Bitwise OR to put the bytes together.

    private fun unpack16Bit(
        bytes: ByteArray,
        i: Int
    ): Long {
        return ((bytes[i].toInt() and 0xff)
                or (bytes[i + 1].toInt() and 0xff shl 8)).toLong()
    }


    fun pack(
        samples: FloatArray,
        bytes: ByteArray,
        slen: Int
    ): Int {
        val bitsPerSample: Int = 16
        val bytesPerSample: Int = 2
        val fullScale = fullScale(bitsPerSample)
        var i = 0
        var s = 0
        while (s < slen) {
            val sample = samples[s]
            val temp: Long = (sample * fullScale).toLong()

            pack16Bit(bytes, i, temp)
            i += bytesPerSample
            s++
        }
        return i
    }

    private fun pack16Bit(
        bytes: ByteArray,
        i: Int,
        temp: Long
    ) {
        bytes[i] = (temp and 0xff).toByte()
        bytes[i + 1] = ((temp ushr 8) and 0xff).toByte()
    }

    //	This is done for the PCM-Signed encoding.
    //	The calling method is converting the byte data into long. So the twos-complement sign must be extended.
    //	There are 64 bits per long. So the bits in the sample are first shifted to the left and then the right-shift will do the filling.
    private fun extendSign(temp: Long, bitsPerSample: Int): Long {
        val extensionBits = 64 - bitsPerSample
        return temp shl extensionBits shr extensionBits
    }

    private fun fullScale(bitsPerSample: Int): Double {
        return pow(2.0, (bitsPerSample - 1).toDouble())
    }








}









