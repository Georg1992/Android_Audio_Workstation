package com.georgv.audioworkstation.core.audio.convert

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.georgv.audioworkstation.core.audio.readWavPcmFileInfo
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WavToAacInstrumentedTest {
    @Test
    fun aacKeepsTheFullMix() {
        val wav = File.createTempFile("tone", ".wav")
        writeSine(wav, Rate, Frames)
        val info = checkNotNull(readWavPcmFileInfo(wav.absolutePath))
        val aac = WavToAac(MediaCodecAac()).convert(wav)
        val stored = aac.readBytes()
        assertEquals("AAC1", stored.copyOf(4).decodeToString())
        val pcm = AacToPcm(MediaCodecAac()).wav(Url, info.dataOffset, info.dataOffset + info.dataSize - 1L) { start, end ->
            stored.copyOfRange(start.toInt(), end.toInt() + 1)
        }
        assertEquals(info.dataSize, pcm.size.toLong())
        val report = compare(wav, info.dataOffset, pcm)
        aac.delete()
        wav.delete()
        val aligned = report.substringAfter("aligned=").substringBefore(" ").toLong()
        assertTrue(report, aligned < ErrorLimit)
    }

    private fun compare(wav: File, dataOffset: Long, decoded: ByteArray): String {
        val original = wav.readBytes().copyOfRange(dataOffset.toInt(), dataOffset.toInt() + decoded.size)
        val samples = original.size / 2
        var peak = 0
        var index = 0
        while (index + 1 < decoded.size) {
            peak = maxOf(peak, abs(sample(decoded, index)))
            index += 2
        }
        var bestLag = 0
        var best = Long.MAX_VALUE
        var lag = -Search
        while (lag <= Search) {
            val error = meanAt(original, decoded, lag)
            if (error < best) {
                best = error
                bestLag = lag
            }
            lag += SearchStep
        }
        val aligned = meanAt(original, decoded, 0)
        return "aligned=$aligned bestLag=$bestLag best=$best peak=$peak samples=$samples"
    }

    private fun meanAt(original: ByteArray, decoded: ByteArray, lag: Int): Long {
        var error = 0L
        var count = 0
        var frame = Search
        val frames = original.size / 2
        while (frame < frames - Search) {
            val other = frame + lag
            if (other in 0 until frames) {
                error += abs(sample(original, frame * 2) - sample(decoded, other * 2))
                count += 1
            }
            frame += 1
        }
        if (count == 0) return Long.MAX_VALUE
        return error / count
    }

    private fun sample(bytes: ByteArray, index: Int): Int {
        val low = bytes[index].toInt() and ByteMask
        val high = bytes[index + 1].toInt()
        return (low or (high shl ByteShift)).toShort().toInt()
    }

    private fun writeSine(file: File, rate: Int, frames: Int) {
        val samples = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            val value = (sin(frame * ToneCycles * Pi / rate) * ToneLevel).toInt().toShort()
            samples[frame * 2] = value
            samples[frame * 2 + 1] = value
        }
        FileOutputStream(file).use { out ->
            val dataSize = samples.size * 2
            out.write("RIFF".toByteArray())
            out.write(le32(36 + dataSize))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(le32(16))
            out.write(le16(1))
            out.write(le16(2))
            out.write(le32(rate))
            out.write(le32(rate * 4))
            out.write(le16(4))
            out.write(le16(16))
            out.write("data".toByteArray())
            out.write(le32(dataSize))
            for (sample in samples) out.write(le16(sample.toInt() and 0xFFFF))
        }
    }

    private fun le16(value: Int) = byteArrayOf((value and ByteMask).toByte(), ((value shr ByteShift) and ByteMask).toByte())

    private fun le32(value: Int) = byteArrayOf(
        (value and ByteMask).toByte(),
        ((value shr ByteShift) and ByteMask).toByte(),
        ((value shr ShortShift) and ByteMask).toByte(),
        ((value shr IntShift) and ByteMask).toByte(),
    )

    private companion object {
        const val Rate = 44_100
        const val Frames = 132_300
        const val ToneCycles = 2.0 * 440.0
        const val ToneLevel = 8_000.0
        const val Pi = 3.141592653589793
        const val ErrorLimit = 1_500L
        const val Search = 20_000
        const val SearchStep = 32
        const val Url = "file"
        const val ByteMask = 0xFF
        const val ByteShift = 8
        const val ShortShift = 16
        const val IntShift = 24
    }
}
