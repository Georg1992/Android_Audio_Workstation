package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.audio.waveform.WavWaveformPeakExtractor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WavPunchSplicerTest {

    private val splicer = WavPunchSplicer()
    private val sampleRateHz = 1_000

    @Test
    fun `splice overwrites middle region and preserves prefix and suffix`() {
        val dir = tempDir()
        val original = File(dir, "original.wav")
        val recording = File(dir, "recording.wav")
        val finalPath = File(dir, "final.wav").absolutePath

        writeConstantPcm16Wav(original, sampleValue = 1_000, frameCount = 20_000)
        writeConstantPcm16Wav(recording, sampleValue = 2_000, frameCount = 3_000)

        val result =
            splicer.splice(
                originalWavPath = original.absolutePath,
                tempRecordingWavPath = recording.absolutePath,
                finalWavPath = finalPath,
                spliceStartInClipMs = 5_000L,
                expectedSampleRateHz = sampleRateHz,
            )

        assertEquals(20_000L, result.durationMs)
        assertEquals(finalPath, result.outputPath)
        assertEquals(1_000.toShort(), readMonoSampleAtFrame(File(finalPath), 4_999))
        assertEquals(2_000.toShort(), readMonoSampleAtFrame(File(finalPath), 5_000))
        assertEquals(2_000.toShort(), readMonoSampleAtFrame(File(finalPath), 7_999))
        assertNotEquals(0.toShort(), readMonoSampleAtFrame(File(finalPath), 6_000))
        assertEquals(1_000.toShort(), readMonoSampleAtFrame(File(finalPath), 8_000))
        assertEquals(1_000.toShort(), readMonoSampleAtFrame(File(finalPath), 19_999))
        assertFalse(recording.exists())
    }

    @Test
    fun `splice overwrites from beginning`() {
        val dir = tempDir()
        val original = File(dir, "original.wav")
        val recording = File(dir, "recording.wav")
        val finalPath = File(dir, "final.wav").absolutePath

        writeConstantPcm16Wav(original, sampleValue = 1_000, frameCount = 20_000)
        writeConstantPcm16Wav(recording, sampleValue = 2_000, frameCount = 3_000)

        splicer.splice(
            originalWavPath = original.absolutePath,
            tempRecordingWavPath = recording.absolutePath,
            finalWavPath = finalPath,
            spliceStartInClipMs = 0L,
            expectedSampleRateHz = sampleRateHz,
        )

        assertEquals(2_000.toShort(), readMonoSampleAtFrame(File(finalPath), 0))
        assertEquals(2_000.toShort(), readMonoSampleAtFrame(File(finalPath), 2_999))
        assertEquals(1_000.toShort(), readMonoSampleAtFrame(File(finalPath), 3_000))
        assertEquals(1_000.toShort(), readMonoSampleAtFrame(File(finalPath), 19_999))
    }

    @Test
    fun `splice extends track when recording runs beyond old end`() {
        val dir = tempDir()
        val original = File(dir, "original.wav")
        val recording = File(dir, "recording.wav")
        val finalPath = File(dir, "final.wav").absolutePath

        writeConstantPcm16Wav(original, sampleValue = 1_000, frameCount = 20_000)
        writeConstantPcm16Wav(recording, sampleValue = 2_000, frameCount = 5_000)

        val result =
            splicer.splice(
                originalWavPath = original.absolutePath,
                tempRecordingWavPath = recording.absolutePath,
                finalWavPath = finalPath,
                spliceStartInClipMs = 18_000L,
                expectedSampleRateHz = sampleRateHz,
            )

        assertEquals(23_000L, result.durationMs)
        assertEquals(1_000.toShort(), readMonoSampleAtFrame(File(finalPath), 17_999))
        assertEquals(2_000.toShort(), readMonoSampleAtFrame(File(finalPath), 18_000))
        assertEquals(2_000.toShort(), readMonoSampleAtFrame(File(finalPath), 22_999))
    }

    @Test
    fun `splice inserts silence when record starts after old end`() {
        val dir = tempDir()
        val original = File(dir, "original.wav")
        val recording = File(dir, "recording.wav")
        val finalPath = File(dir, "final.wav").absolutePath

        writeConstantPcm16Wav(original, sampleValue = 1_000, frameCount = 20_000)
        writeConstantPcm16Wav(recording, sampleValue = 2_000, frameCount = 3_000)

        val result =
            splicer.splice(
                originalWavPath = original.absolutePath,
                tempRecordingWavPath = recording.absolutePath,
                finalWavPath = finalPath,
                spliceStartInClipMs = 25_000L,
                expectedSampleRateHz = sampleRateHz,
            )

        assertEquals(28_000L, result.durationMs)
        assertEquals(1_000.toShort(), readMonoSampleAtFrame(File(finalPath), 19_999))
        assertEquals(0.toShort(), readMonoSampleAtFrame(File(finalPath), 20_000))
        assertEquals(0.toShort(), readMonoSampleAtFrame(File(finalPath), 24_999))
        assertEquals(2_000.toShort(), readMonoSampleAtFrame(File(finalPath), 25_000))
    }

    @Test
    fun `failed splice leaves original wav unchanged`() {
        val dir = tempDir()
        val original = File(dir, "original.wav")
        val finalPath = File(dir, "final.wav").absolutePath
        writeConstantPcm16Wav(original, sampleValue = 1_000, frameCount = 20_000)
        val before = original.readBytes()

        try {
            splicer.splice(
                originalWavPath = original.absolutePath,
                tempRecordingWavPath = File(dir, "missing.wav").absolutePath,
                finalWavPath = finalPath,
                spliceStartInClipMs = 5_000L,
                expectedSampleRateHz = sampleRateHz,
            )
            error("expected splice failure")
        } catch (_: Exception) {
            assertTrue(original.exists())
            assertTrue(before.contentEquals(original.readBytes()))
            assertFalse(File(finalPath).exists())
        }
    }

    @Test
    fun `spliced wav supports waveform extraction`() {
        val dir = tempDir()
        val original = File(dir, "original.wav")
        val recording = File(dir, "recording.wav")
        val finalPath = File(dir, "final.wav").absolutePath

        writeConstantPcm16Wav(original, sampleValue = 1_000, frameCount = 20_000)
        writeConstantPcm16Wav(recording, sampleValue = 30_000, frameCount = 3_000)

        splicer.splice(
            originalWavPath = original.absolutePath,
            tempRecordingWavPath = recording.absolutePath,
            finalWavPath = finalPath,
            spliceStartInClipMs = 5_000L,
            expectedSampleRateHz = sampleRateHz,
        )

        val peaks =
            kotlinx.coroutines.test.runTest {
                WavWaveformPeakExtractor().extract(finalPath)
            }
        assertNotNull(peaks)
    }

    @Test
    fun `spliced wav waveform reflects prefix new and suffix durations`() = runTest {
        val dir = tempDir()
        val original = File(dir, "original.wav")
        val recording = File(dir, "recording.wav")
        val finalFile = File(dir, "final.wav")

        writeConstantPcm16Wav(original, sampleValue = 1_000, frameCount = 20_000)
        writeConstantPcm16Wav(recording, sampleValue = 30_000, frameCount = 3_000)

        splicer.splice(
            originalWavPath = original.absolutePath,
            tempRecordingWavPath = recording.absolutePath,
            finalWavPath = finalFile.absolutePath,
            spliceStartInClipMs = 5_000L,
            expectedSampleRateHz = sampleRateHz,
        )

        val extractor = WavWaveformPeakExtractor(targetPeakCount = 20)
        val beforePeaks = extractor.extract(original.absolutePath)
        val afterPeaks = extractor.extract(finalFile.absolutePath)

        assertNotNull(beforePeaks)
        assertNotNull(afterPeaks)
        assertEquals(20_000L, beforePeaks!!.sourceDurationMs)
        assertEquals(20_000L, afterPeaks!!.sourceDurationMs)
        assertNotEquals(beforePeaks.amplitudes, afterPeaks.amplitudes)
    }

    @Test
    fun `spliced wav with silence gap shows near-zero peaks in gap region`() = runTest {
        val dir = tempDir()
        val original = File(dir, "original.wav")
        val recording = File(dir, "recording.wav")
        val finalFile = File(dir, "final.wav")

        writeConstantPcm16Wav(original, sampleValue = Short.MAX_VALUE, frameCount = 20_000)
        writeConstantPcm16Wav(recording, sampleValue = Short.MAX_VALUE, frameCount = 3_000)

        splicer.splice(
            originalWavPath = original.absolutePath,
            tempRecordingWavPath = recording.absolutePath,
            finalWavPath = finalFile.absolutePath,
            spliceStartInClipMs = 25_000L,
            expectedSampleRateHz = sampleRateHz,
        )

        val peaks =
            WavWaveformPeakExtractor(targetPeakCount = 28).extract(finalFile.absolutePath)

        assertNotNull(peaks)
        assertEquals(28_000L, peaks!!.sourceDurationMs)
        val silenceBuckets = peaks.amplitudes.slice(20 until 25)
        assertTrue(silenceBuckets.all { it <= 0.01f })
        assertTrue(peaks.amplitudes.first() > 0.5f)
        assertTrue(peaks.amplitudes.last() > 0.5f)
    }

    private fun tempDir(): File =
        File.createTempFile("wav-splice", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
}
