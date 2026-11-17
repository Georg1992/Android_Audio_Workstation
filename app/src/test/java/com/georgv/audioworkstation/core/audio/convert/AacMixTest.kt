package com.georgv.audioworkstation.core.audio.convert

import com.georgv.audioworkstation.core.audio.readWavPcmFileInfo
import com.georgv.audioworkstation.core.audio.writeConstantPcm16Wav
import com.georgv.audioworkstation.ui.playback.AacMixSource
import com.georgv.audioworkstation.ui.playback.MixSource
import com.georgv.audioworkstation.ui.playback.PcmOutput
import com.georgv.audioworkstation.ui.playback.SharedMixStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AacMixTest {
    @Test
    fun aacPiecesRestoreThePcmAndTheFullDuration() {
        val wav = File.createTempFile("mix", ".wav")
        writeConstantPcm16Wav(wav, 7, frameCount = Frames, sampleRateHz = Rate)
        val info = checkNotNull(readWavPcmFileInfo(wav.absolutePath))
        val aac = WavToAac(RoundTripAac()).convert(wav)
        val stored = aac.readBytes()
        val codec = RoundTripAac()
        val pcm = AacToPcm(codec).wav(Url, info.dataOffset, info.dataOffset + info.dataSize - 1L) { start, end ->
            stored.copyOfRange(start.toInt(), end.toInt() + 1)
        }
        assertEquals(info.dataSize, pcm.size.toLong())
        assertEquals(7, pcm[0].toInt())
        val output = HoldingOutput()
        val stream = SharedMixStream(AacMixSource(Memory(stored), codec), output, aheadPieces = 1)
        try {
            stream.play(Url)
            assertEquals(info.durationMs.toInt(), stream.transport().durationMs)
            assertTrue(output.started)
        } finally {
            stream.release()
            aac.delete()
            wav.delete()
            Unit
        }
    }

    private class Memory(private val bytes: ByteArray) : MixSource {
        override fun open(url: String, start: Long, endInclusive: Long): InputStream {
            val from = start.toInt()
            val to = endInclusive.toInt() + 1
            return ByteArrayInputStream(bytes.copyOfRange(from, to))
        }
    }

    private class HoldingOutput : PcmOutput {
        var started = false

        override fun start(sampleRateHz: Int, channelCount: Int) {
            started = true
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int): Int = 0

        override fun pause() = Unit

        override fun play() = Unit

        override fun release() = Unit
    }

    private companion object {
        const val Frames = 4_000
        const val Rate = 1_000
        const val Url = "https://mix.example/aac"
    }
}
