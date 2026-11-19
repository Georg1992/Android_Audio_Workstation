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
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun seekStartsTheAacMixAtTheNewTimeAndKeepsPlaying() {
        val wav = File.createTempFile("mix", ".wav")
        writeConstantPcm16Wav(wav, Sample, frameCount = Frames, sampleRateHz = Rate)
        val aac = WavToAac(RoundTripAac()).convert(wav)
        val stored = aac.readBytes()
        val output = RecordingOutput()
        val stream = SharedMixStream(AacMixSource(Memory(stored), RoundTripAac()), output, aheadPieces = 1)
        try {
            stream.play(Url)
            stream.seekTo(SeekMs)
            val atSeek = stream.transport()
            output.allow(WritesAfterSeek)
            val moved = waitUntil { stream.transport().positionMs > SeekMs && stream.transport().playing }
            val transport = stream.transport()
            assertTrue(
                "seek=${atSeek.positionMs}/${atSeek.playing} pos=${transport.positionMs} playing=${transport.playing} writes=${output.writes}",
                moved,
            )
            assertEquals(Sample.toInt(), output.firstSample())
        } finally {
            output.allow(WritesAfterSeek)
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

    private class RecordingOutput : PcmOutput {
        private val permits = AtomicInteger(0)
        var writes = 0
        private var sample = 0

        fun allow(count: Int) {
            permits.addAndGet(count)
        }

        fun firstSample(): Int = sample

        override fun start(sampleRateHz: Int, channelCount: Int) = Unit

        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            writes += 1
            while (permits.get() == 0) Thread.sleep(PollMs)
            permits.decrementAndGet()
            if (length >= 2) {
                val low = bytes[offset].toInt() and ByteMask
                val high = bytes[offset + 1].toInt()
                sample = (low or (high shl ByteShift)).toShort().toInt()
            }
            return length
        }

        override fun pause() = Unit

        override fun play() = Unit

        override fun release() = Unit
    }

    private fun waitUntil(probe: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WaitMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return true
            Thread.sleep(PollMs)
        }
        return probe()
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
        const val Frames = 20_000
        const val Rate = 1_000
        const val SeekMs = 2_500
        const val Sample: Short = 7
        const val Url = "https://mix.example/aac"
        const val WritesAfterSeek = 2
        const val WaitMs = 3_000L
        const val PollMs = 10L
        const val ByteMask = 0xFF
        const val ByteShift = 8
    }
}
