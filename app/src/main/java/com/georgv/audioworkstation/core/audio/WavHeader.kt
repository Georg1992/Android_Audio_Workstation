package com.georgv.audioworkstation.core.audio

class WavHeader(
    private var dataSize: Long,
    private val sampleRate: Int,
    channels: Int,
    bitDepth: Int
) {
    private val header = ByteArray(44)
    private val channels: Short = channels.toShort()
    private val bitDepth: Short = bitDepth.toShort()

    init {
        buildHeader()
    }

    private fun buildHeader() {
        // RIFF chunk descriptor
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        // RIFF chunk size (dataSize + 36)
        header[4] = (dataSize + 36).toByte()
        header[5] = (dataSize + 36 shr 8).toByte()
        header[6] = (dataSize + 36 shr 16).toByte()
        header[7] = (dataSize + 36 shr 24).toByte()
        // WAVE
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        // fmt chunk size
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        // audio format
        header[20] = 1
        header[21] = 0
        // number of channels
        header[22] = channels.toByte()
        header[23] = 0
        // sample rate
        header[24] = (sampleRate).toByte()
        header[25] = (sampleRate shr 8).toByte()
        header[26] = (sampleRate shr 16).toByte()
        header[27] = (sampleRate shr 24).toByte()
        val byteRate = sampleRate * channels * bitDepth / 8
        header[28] = (byteRate).toByte()
        header[29] = (byteRate shr 8).toByte()
        header[30] = (byteRate shr 16).toByte()
        header[31] = (byteRate shr 24).toByte()
        // block align
        header[32] = (channels * bitDepth / 8).toByte()
        header[33] = 0
        // bits per sample
        header[34] = bitDepth.toByte()
        header[35] = 0
        // data
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        // data chunk size
        header[40] = (dataSize).toByte()
        header[41] = (dataSize shr 8).toByte()
        header[42] = (dataSize shr 16).toByte()
        header[43] = (dataSize shr 24).toByte()


    }
    fun getHeader(): ByteArray {
        return header
    }
}
