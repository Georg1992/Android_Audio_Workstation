package com.georgv.audioworkstation.core.audio

import java.io.File
import java.io.FileOutputStream

internal fun writeConstantPcm16Wav(
    file: File,
    sampleValue: Short,
    frameCount: Int,
    channelCount: Int = 1,
    sampleRateHz: Int = 1_000,
) {
    val samples = ShortArray(frameCount * channelCount) { sampleValue }
    file.parentFile?.mkdirs()
    file.writePcm16Wav(samples, channelCount, sampleRateHz)
}

internal fun readMonoSampleAtFrame(file: File, frameIndex: Int): Short? {
    val info = readWavPcmFileInfo(file.absolutePath) ?: return null
    if (info.channelCount != 1) return null
    return java.io.RandomAccessFile(file, "r").use { wav ->
        wav.seek(info.dataOffset + frameIndex * info.blockAlign)
        val lo = wav.read()
        val hi = wav.read()
        if (lo < 0 || hi < 0) return null
        ((lo and 0xFF) or (hi shl 8)).toShort()
    }
}

private fun File.writePcm16Wav(samples: ShortArray, channelCount: Int, sampleRateHz: Int) {
    FileOutputStream(this).use { out ->
        val dataSize = samples.size * 2
        out.writeAscii("RIFF")
        out.writeUInt32Le(36 + dataSize)
        out.writeAscii("WAVE")
        out.writeAscii("fmt ")
        out.writeUInt32Le(16)
        out.writeUInt16Le(1)
        out.writeUInt16Le(channelCount)
        out.writeUInt32Le(sampleRateHz)
        out.writeUInt32Le(sampleRateHz * channelCount * 2)
        out.writeUInt16Le(channelCount * 2)
        out.writeUInt16Le(16)
        out.writeAscii("data")
        out.writeUInt32Le(dataSize)
        samples.forEach { out.writeUInt16Le(it.toInt() and 0xFFFF) }
    }
}

private fun FileOutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun FileOutputStream.writeUInt16Le(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
}

private fun FileOutputStream.writeUInt32Le(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
    write((value shr 16) and 0xFF)
    write((value shr 24) and 0xFF)
}
