package com.georgv.audioworkstation.customHandlers

import androidx.room.TypeConverter
import java.io.*
import java.util.*
import com.google.gson.Gson

import com.google.gson.reflect.TypeToken
import org.apache.commons.io.FileUtils


private const val TRANSFER_BUFFER_SIZE = 10 * 1024

object TypeConverter {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date): Long {
        return date.time
    }

    @TypeConverter
    fun fromString(value: String?): ArrayList<String?>? {
        val listType = object : TypeToken<ArrayList<String?>?>() {}.type
        return Gson().fromJson(value, listType)
    }

    @TypeConverter
    fun fromArrayList(list: ArrayList<String?>?): String? {
        val gson = Gson()
        return gson.toJson(list)
    }


    @Throws(IOException::class)
    fun pcmToWav(
        input: File,
        output: File,
        channelCount: Int,
        originSampleRate: Int,
        sampleRate: Int,
        bitsPerSample: Int
    ) {
        val inputSize = input.length().toInt()
        val sampleFactor: Float = sampleRate.toFloat() / originSampleRate.toFloat()

        FileOutputStream(output).use { encoded ->
            // WAVE RIFF header
            writeToOutput(encoded, "RIFF") // chunk id
            writeToOutput(encoded, 36 + (inputSize.toFloat() * sampleFactor).toInt()) // chunk size
            writeToOutput(encoded, "WAVE") // format
            // SUB CHUNK 1 (FORMAT)
            writeToOutput(encoded, "fmt ") // subchunk 1 id
            writeToOutput(encoded, 16) // subchunk 1 size
            writeToOutput(encoded, 1.toShort()) // audio format (1 = PCM)
            writeToOutput(encoded, channelCount.toShort()) // number of channelCount
            writeToOutput(encoded, sampleRate) // sample rate
            writeToOutput(encoded, sampleRate * channelCount * bitsPerSample / 8) // byte rate
            writeToOutput(encoded, (channelCount * bitsPerSample / 8).toShort()) // block align
            writeToOutput(encoded, bitsPerSample.toShort()) // bits per sample

            // SUB CHUNK 2 (AUDIO DATA)
            if (originSampleRate == sampleRate) {
                writeToOutput(encoded, "data") // subchunk 2 id
                writeToOutput(encoded, inputSize) // subchunk 2 size
                copy(FileInputStream(input), encoded)
            }

            ///We might need samplerate convertion in the future
//            else {
//                writeToOutput(encoded, "data") // subchunk 2 id
//                writeToOutput(
//                    encoded,
//                    (inputSize.toFloat() * sampleFactor).toInt()
//                ) // subchunk 2 size
//
//                val inputStream = FileInputStream(input)
//                val buffer = ByteArray(2)
//                var len: Int = inputStream.read(buffer)
//                var overFlow = 0
//                while (len != -1) {
//                    for (i in 0 until (sampleFactor * 1000F).toInt()) {
//                        overFlow += 1
//                        if (overFlow >= 1000) {
//                            encoded.write(buffer, 0, len)
//                            overFlow = 0
//                        }
//                    }
//                    len = inputStream.read(buffer)
//                }
//                inputStream.close()
//            }

        }

    }


    @Throws(IOException::class)
    fun writeToOutput(output: OutputStream, data: String) {
        for (element in data) {
            output.write(element.code)
        }
    }

    @Throws(IOException::class)
    fun writeToOutput(output: OutputStream, data: Int) {
        output.write(data shr 0)
        output.write(data shr 8)
        output.write(data shr 16)
        output.write(data shr 24)
    }

    @Throws(IOException::class)
    fun writeToOutput(output: OutputStream, data: Short) {
        output.write(data.toInt() shr 0)
        output.write(data.toInt() shr 8)
    }

    @Throws(IOException::class)
    fun copy(source: InputStream, output: OutputStream): Long {
        return copy(source, output, TRANSFER_BUFFER_SIZE)
    }

    @Throws(IOException::class)
    fun copy(source: InputStream, output: OutputStream, bufferSize: Int): Long {
        var read = 0L
        val buffer = ByteArray(bufferSize)
        var n: Int
        while (source.read(buffer).also { n = it } != -1) {
            output.write(buffer, 0, n)
            read += n.toLong()
        }
        return read
    }




}