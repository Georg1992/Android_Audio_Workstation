package com.georgv.audioworkstation.customHandlers

import android.media.MediaDataSource
import com.georgv.audioworkstation.audioprocessing.Reverb
import com.georgv.audioworkstation.data.Track
import java.io.File
import java.io.FileInputStream



class CustomDataSource(track: Track) : MediaDataSource() {
    private val file = File(track.wavDir)
    private var fileInputStream = FileInputStream(file)
    private val fileLengthNoHeader = file.length().toInt() -44
    var lastReadEndPosition = 0L

    init {
        fileInputStream.skip(44) //skipping the header
    }


    private fun processingAudio(audioToProcess: ByteArray): ByteArray {

        return byteArrayOf()
    }

    override fun readAt(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
        if (buffer == null || size <= 0) return 0
        if (position >= fileLengthNoHeader)
            return -1
        if (position < lastReadEndPosition) {
            fileInputStream.close()
            lastReadEndPosition = 0
            fileInputStream = FileInputStream(file)
        }
        val skipped = fileInputStream.skip(position - lastReadEndPosition)
        return if (skipped == position - lastReadEndPosition) {
            val tmpBuff = ByteArray(size)
            val bytesRead = fileInputStream.read(tmpBuff, offset, size)

            val processedBuffer = processingAudio(tmpBuff)
            System.arraycopy(processedBuffer, 0, buffer, offset, processedBuffer.size)

            lastReadEndPosition = position + bytesRead
            bytesRead
        } else {
            -1
        }
    }

    override fun getSize(): Long {
        return fileLengthNoHeader.toLong()*4
    }

    override fun close() {
        fileInputStream.close()
    }
}