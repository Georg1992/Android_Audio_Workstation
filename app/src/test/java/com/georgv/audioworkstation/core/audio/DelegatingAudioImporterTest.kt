package com.georgv.audioworkstation.core.audio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class DelegatingAudioImporterTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `returns unsupported encoding for unknown bytes without uri source`() = runTest {
        val importer =
            DelegatingAudioImporter(
                wavAudioImporter = WavAudioImporter(),
                mediaCodecAudioImporter = MediaCodecAudioImporter(context),
            )
        val source = AudioImportSource { ByteArrayInputStream(ByteArray(12) { 0 }) }

        val result =
            importer.import(
                source = source,
                destinationPath = "/tmp/out.wav",
                target =
                    AudioImportTarget(
                        sampleRate = 48_000,
                        fileBitDepth = 16,
                        channelMode = ChannelMode.STEREO,
                    ),
            )

        assertEquals(AudioImportResult.Failure.UnsupportedEncoding, result)
    }

    @Test
    fun `returns unsupported encoding for compressed bytes without uri source`() = runTest {
        val importer =
            DelegatingAudioImporter(
                wavAudioImporter = WavAudioImporter(),
                mediaCodecAudioImporter = MediaCodecAudioImporter(context),
            )
        val source =
            AudioImportSource {
                ByteArrayInputStream(
                    byteArrayOf(
                        'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3,
                        0, 0, 0, 0, 0, 0, 0, 0,
                    ),
                )
            }

        val result =
            importer.import(
                source = source,
                destinationPath = "/tmp/out.wav",
                target =
                    AudioImportTarget(
                        sampleRate = 48_000,
                        fileBitDepth = 16,
                        channelMode = ChannelMode.STEREO,
                    ),
            )

        assertEquals(AudioImportResult.Failure.UnsupportedEncoding, result)
    }
}
