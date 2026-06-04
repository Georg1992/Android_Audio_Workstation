package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class AudioImportFormatDetectorTest {

    @Test
    fun `detectImportFormat recognizes wav header`() {
        val source =
            AudioImportSource {
                ByteArrayInputStream(
                    byteArrayOf(
                        'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
                        0, 0, 0, 0,
                        'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
                    ),
                )
            }

        assertEquals(DetectedImportFormat.PcmWav, detectImportFormat(source))
    }

    @Test
    fun `detectImportFormat recognizes mp3 id3 header`() {
        val source =
            AudioImportSource {
                ByteArrayInputStream(
                    byteArrayOf(
                        'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3,
                        0, 0, 0, 0, 0, 0, 0, 0,
                    ),
                )
            }

        assertEquals(DetectedImportFormat.CompressedAudio, detectImportFormat(source))
    }

    @Test
    fun `detectImportFormat recognizes mp3 frame sync`() {
        val source =
            AudioImportSource {
                ByteArrayInputStream(
                    byteArrayOf(
                        0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00,
                        0, 0, 0, 0, 0, 0, 0, 0,
                    ),
                )
            }

        assertEquals(DetectedImportFormat.CompressedAudio, detectImportFormat(source))
    }

    @Test
    fun `detectImportFormat returns unknown for unrelated bytes`() {
        val source = AudioImportSource { ByteArrayInputStream(ByteArray(12) { 0 }) }

        assertEquals(DetectedImportFormat.Unknown, detectImportFormat(source))
    }
}
