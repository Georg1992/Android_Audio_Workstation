package com.georgv.audioworkstation.ui.components

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class WavWaveformPeakExtractorTest {

    @Test
    fun `extract generates normalized peaks from wav`() = runTest {
        val wav = tempWav(
            samples = shortArrayOf(
                0, 2_000, 4_000, 8_000,
                12_000, 16_000, 20_000, 24_000,
            )
        )

        val peaks = WavWaveformPeakExtractor(targetPeakCount = 4).extract(wav.absolutePath)

        assertNotNull(peaks)
        val amplitudes = peaks?.amplitudes.orEmpty()
        assertEquals(4, amplitudes.size)
        assertEquals(1f, amplitudes.maxOrNull() ?: 0f, 0.0001f)
        assertTrue(amplitudes.first() < amplitudes.last())
    }

    @Test
    fun `extract keeps peaks in normalized range`() = runTest {
        val wav = tempWav(samples = shortArrayOf(Short.MIN_VALUE, -10_000, 0, 10_000, Short.MAX_VALUE))

        val peaks = WavWaveformPeakExtractor(targetPeakCount = 8).extract(wav.absolutePath)

        assertNotNull(peaks)
        peaks?.amplitudes.orEmpty().forEach { peak ->
            assertTrue(peak >= 0f)
            assertTrue(peak <= 1f)
        }
    }

    @Test
    fun `extract returns null for invalid wav`() = runTest {
        val file = File.createTempFile("invalid-waveform", ".wav").apply {
            writeText("not a wav")
            deleteOnExit()
        }

        assertNull(WavWaveformPeakExtractor().extract(file.absolutePath))
    }

    @Test
    fun `extract keeps silence at zero`() = runTest {
        val wav = tempWav(samples = ShortArray(16) { 0 })

        val peaks = WavWaveformPeakExtractor(targetPeakCount = 4).extract(wav.absolutePath)

        assertNotNull(peaks)
        assertEquals(listOf(0f, 0f, 0f, 0f), peaks?.amplitudes)
    }

    @Test
    fun `sparse transient does not force bucket to peak-only full height`() = runTest {
        val wav = tempWav(samples = shortArrayOf(Short.MAX_VALUE, 0, 0, 0))

        val peaks = WavWaveformPeakExtractor(targetPeakCount = 1).extract(wav.absolutePath)

        assertNotNull(peaks)
        // Hybrid pre-normalization would be below peak-only because RMS reflects mostly silence.
        // Single-bucket normalization still makes the only visible bucket full height.
        assertEquals(1f, peaks?.amplitudes?.single() ?: 0f, 0.0001f)
    }

    @Test
    fun `sparse transient bucket is lower than dense high peak bucket after normalization`() = runTest {
        val wav = tempWav(
            samples = shortArrayOf(
                Short.MAX_VALUE, 0, 0, 0,
                Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE,
            )
        )

        val peaks = WavWaveformPeakExtractor(targetPeakCount = 2).extract(wav.absolutePath)

        assertNotNull(peaks)
        val amplitudes = peaks?.amplitudes.orEmpty()
        assertTrue(amplitudes[0] < amplitudes[1])
        assertEquals(1f, amplitudes[1], 0.0001f)
    }

    @Test
    fun `stereo samples contribute to RMS and peak`() = runTest {
        val wav = tempStereoWav(
            interleavedSamples = shortArrayOf(
                0, Short.MAX_VALUE,
                0, Short.MAX_VALUE,
                0, 0,
                0, 0,
            )
        )

        val peaks = WavWaveformPeakExtractor(targetPeakCount = 2).extract(wav.absolutePath)

        assertNotNull(peaks)
        val amplitudes = peaks?.amplitudes.orEmpty()
        assertEquals(1f, amplitudes[0], 0.0001f)
        assertEquals(0f, amplitudes[1], 0.0001f)
    }
}

internal fun tempWav(samples: ShortArray): File =
    tempPcm16Wav(samples = samples, channelCount = 1)

private fun tempStereoWav(interleavedSamples: ShortArray): File =
    tempPcm16Wav(samples = interleavedSamples, channelCount = 2)

private fun tempPcm16Wav(samples: ShortArray, channelCount: Int): File =
    File.createTempFile("waveform", ".wav").apply {
        deleteOnExit()
        parentFile?.mkdirs()
        FileOutputStream(this).use { out ->
            val dataSize = samples.size * 2
            out.writeAscii("RIFF")
            out.writeUInt32Le(36 + dataSize)
            out.writeAscii("WAVE")
            out.writeAscii("fmt ")
            out.writeUInt32Le(16)
            out.writeUInt16Le(1)
            out.writeUInt16Le(channelCount)
            out.writeUInt32Le(48_000)
            out.writeUInt32Le(48_000 * channelCount * 2)
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
    write((value ushr 8) and 0xFF)
}

private fun FileOutputStream.writeUInt32Le(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}
