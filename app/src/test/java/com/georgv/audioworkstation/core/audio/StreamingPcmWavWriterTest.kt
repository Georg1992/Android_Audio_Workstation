package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StreamingPcmWavWriterTest {

    @Test
    fun `writes canonical wav with patched data size`() {
        val destination = File.createTempFile("streaming-wav", ".wav").apply { deleteOnExit() }
        val samples = shortArrayOf(100, -100, 200, -200)

        StreamingPcmWavWriter(
            file = destination,
            sampleRate = 48_000,
            channelCount = 2,
        ).use { writer ->
            writer.writePcmInt16(samples, samples.size)
            assertEquals(2L, writer.writtenFrameCount())
        }

        assertTrue(destination.length() > 44L)
        val header = parseWavHeader(destination.inputStream())
        assertTrue(header != null)
        assertEquals(2, header!!.numChannels)
        assertEquals(48_000, header.sampleRate)
        assertEquals(16, header.bitsPerSample)
        assertEquals(8, header.dataSizeBytes)
    }

    @Test
    fun `writePcm16FromByteBuffer matches writePcmInt16 output`() {
        val samples = shortArrayOf(100, -100, 200, -200)
        val byteBuffer =
            ByteBuffer.allocate(samples.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { byteBuffer.putShort(it) }
        byteBuffer.flip()

        val fromShortArray = File.createTempFile("streaming-wav-short", ".wav").apply { deleteOnExit() }
        StreamingPcmWavWriter(
            file = fromShortArray,
            sampleRate = 48_000,
            channelCount = 2,
        ).use { writer ->
            writer.writePcmInt16(samples, samples.size)
        }

        val fromByteBuffer = File.createTempFile("streaming-wav-buffer", ".wav").apply { deleteOnExit() }
        StreamingPcmWavWriter(
            file = fromByteBuffer,
            sampleRate = 48_000,
            channelCount = 2,
        ).use { writer ->
            writer.writePcm16FromByteBuffer(byteBuffer, 0, byteBuffer.remaining())
        }

        assertEquals(fromShortArray.length(), fromByteBuffer.length())
        assertTrue(fromShortArray.readBytes().contentEquals(fromByteBuffer.readBytes()))
    }
}
