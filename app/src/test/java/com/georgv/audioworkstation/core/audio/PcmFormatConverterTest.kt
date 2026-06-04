package com.georgv.audioworkstation.core.audio

import android.media.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmFormatConverterTest {

    @Test
    fun `decodeOutputBuffer converts pcm16 little endian`() {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(1000)
        buffer.putShort(-2000)
        buffer.flip()

        val decoded =
            PcmFormatConverter.decodeOutputBuffer(
                buffer = buffer,
                offset = 0,
                sizeBytes = 4,
                encoding = AudioFormat.ENCODING_PCM_16BIT,
                channelCount = 2,
            )

        assertArrayEquals(shortArrayOf(1000, -2000), decoded)
    }

    @Test
    fun `decodeOutputBuffer converts pcm float to int16`() {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        buffer.putFloat(0.5f)
        buffer.putFloat(-1f)
        buffer.flip()

        val decoded =
            PcmFormatConverter.decodeOutputBuffer(
                buffer = buffer,
                offset = 0,
                sizeBytes = 8,
                encoding = AudioFormat.ENCODING_PCM_FLOAT,
                channelCount = 2,
            )

        assertEquals(2, decoded.size)
        assertTrue(decoded[0] > 16_000)
        assertTrue(decoded[1] < -30_000)
    }

    @Test
    fun `decodeOutputBuffer honors buffer offset`() {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0)
        buffer.putShort(1000)
        buffer.putShort(-2000)
        buffer.putShort(3000)

        val decoded =
            PcmFormatConverter.decodeOutputBuffer(
                buffer = buffer,
                offset = 4,
                sizeBytes = 4,
                encoding = AudioFormat.ENCODING_PCM_16BIT,
                channelCount = 2,
            )

        assertArrayEquals(shortArrayOf(-2000, 3000), decoded)
    }

    @Test(expected = UnsupportedPcmEncodingException::class)
    fun `decodeOutputBuffer rejects unknown encoding`() {
        val buffer = ByteBuffer.allocate(4)
        PcmFormatConverter.decodeOutputBuffer(
            buffer = buffer,
            offset = 0,
            sizeBytes = 4,
            encoding = 99,
            channelCount = 1,
        )
    }
}
