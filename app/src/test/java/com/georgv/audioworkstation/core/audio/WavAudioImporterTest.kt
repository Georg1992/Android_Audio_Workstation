package com.georgv.audioworkstation.core.audio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class WavAudioImporterTest {

    private val importer = WavAudioImporter()
    private val target =
        AudioImportTarget(
            sampleRate = 48_000,
            fileBitDepth = 16,
            channelMode = ChannelMode.STEREO,
        )

    @Test
    fun `import accepts mono wav and reports channel count`() = runTest {
        val sourceFile = importWav(samples = shortArrayOf(0, 1_000, 2_000, 3_000), channelCount = 1)
        val destination = File.createTempFile("import-mono", ".wav").apply { deleteOnExit() }

        val result = importFromFile(sourceFile, destination)

        assertTrue(result is AudioImportResult.Success)
        val success = result as AudioImportResult.Success
        assertEquals(ChannelMode.MONO, success.channelMode)
        assertEquals(1, success.channelCount)
        assertTrue(destination.length() > 44L)
    }

    @Test
    fun `import accepts stereo wav and reports channel count`() = runTest {
        val sourceFile =
            importWav(
                samples = shortArrayOf(0, 1_000, 2_000, 3_000, 4_000, 5_000, 6_000, 7_000),
                channelCount = 2,
            )
        val destination = File.createTempFile("import-stereo", ".wav").apply { deleteOnExit() }

        val result = importFromFile(sourceFile, destination)

        assertTrue(result is AudioImportResult.Success)
        val success = result as AudioImportResult.Success
        assertEquals(ChannelMode.STEREO, success.channelMode)
        assertEquals(2, success.channelCount)
    }

    @Test
    fun `import rejects more than two channels`() = runTest {
        val sourceFile =
            importWav(
                samples = ShortArray(8) { (it * 100).toShort() },
                channelCount = 4,
            )
        val destination = File.createTempFile("import-quad", ".wav").apply { deleteOnExit() }

        val result = importFromFile(sourceFile, destination)

        assertEquals(AudioImportResult.Failure.UnsupportedChannelCount, result)
    }

    @Test
    fun `import preserves sample rate validation`() = runTest {
        val sourceFile =
            importWav(samples = shortArrayOf(0, 1_000), channelCount = 1, sampleRateHz = 44_100)
        val destination = File.createTempFile("import-rate", ".wav").apply { deleteOnExit() }

        val result = importFromFile(sourceFile, destination)

        assertTrue(result is AudioImportResult.Failure.SampleRateMismatch)
        val mismatch = result as AudioImportResult.Failure.SampleRateMismatch
        assertEquals(48_000, mismatch.expected)
        assertEquals(44_100, mismatch.actual)
    }

    private suspend fun importFromFile(sourceFile: File, destination: File): AudioImportResult =
        importer.import(
            source = AudioImportSource { FileInputStream(sourceFile) },
            destinationPath = destination.absolutePath,
            target = target,
        )

    private fun importWav(
        samples: ShortArray,
        channelCount: Int,
        sampleRateHz: Int = 48_000,
    ): File =
        File.createTempFile("wav-import-src", ".wav").apply {
            deleteOnExit()
            writePcm16Wav(samples, channelCount, sampleRateHz)
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
    write((value ushr 8) and 0xFF)
}

private fun FileOutputStream.writeUInt32Le(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}
