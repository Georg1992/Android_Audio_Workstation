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
}

internal fun tempWav(samples: ShortArray): File =
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
            out.writeUInt16Le(1)
            out.writeUInt32Le(48_000)
            out.writeUInt32Le(48_000 * 2)
            out.writeUInt16Le(2)
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
