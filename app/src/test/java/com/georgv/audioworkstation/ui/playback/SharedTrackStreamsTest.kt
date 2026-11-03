package com.georgv.audioworkstation.ui.playback

import com.georgv.audioworkstation.core.audio.writeConstantPcm16Wav
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.online.share.SharedPlayback
import com.georgv.audioworkstation.online.share.SharedProject
import com.georgv.audioworkstation.online.share.SharedProjects
import com.georgv.audioworkstation.online.share.SharedTake
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTrackStreamsTest {
    @Test
    fun eachTrackStreamsItsOwnPieceAndSeekUsesThePieceAtTheNewTime() {
        val wav = wavFrames(FrameCount)
        val ranges = CopyOnWriteArrayList<Requested>()
        val output = PermitOutput()
        val streams = player(wav, ranges, output)
        val tracks = listOf(take("take-a"), take("take-b"))
        try {
            runBlocking { streams.play("song", tracks, 0L, streamClock = true) }
            assertTrue(waitUntil { pieceStarts(ranges).size == tracks.size * (AheadPieces + 1) && stable(ranges) })
            assertTrue(ranges.none { it.start > pieceStart(AheadPieces) })
            streams.seekTo(SeekMs)
            assertEquals(SeekMs, streams.positionMs())
            assertTrue(waitUntil { ranges.any { it.start == TargetStart } })
            val piece = ranges.first { it.start == TargetStart }
            assertEquals(TargetEnd, piece.endInclusive)
            assertTrue(ranges.none { it.start == ExactPcmOffset })
        } finally {
            output.allow(WritesAfterStart)
            streams.release()
        }
    }

    @Test
    fun slowConnectionKeepsTheBufferedPiecePlaying() {
        val wav = wavFrames(FrameCount)
        val ranges = CopyOnWriteArrayList<Requested>()
        val failures = AtomicInteger(0)
        val source = memory(wav, ranges) { start ->
            if (start == pieceStart(1)) {
                failures.incrementAndGet()
                UnknownHostException("Unable to resolve host")
            } else {
                null
            }
        }
        val output = PermitOutput()
        val streams = SharedTrackStreams({ Files() }, source, { output })
        try {
            runBlocking { streams.play("song", listOf(take("take-a")), 0L, streamClock = true) }
            assertTrue(waitUntil { failures.get() > 0 })
            var reported: Exception? = null
            assertFalse(streams.consumeClock { reported = it })
            assertEquals(null, reported)
            output.allow(1)
            assertTrue(waitUntil {
                streams.consumeClock { reported = it }
                reported is UnknownHostException
            })
            assertTrue(streams.ownsClock())
            assertTrue(ranges.filter { it.start == pieceStart(1) }.size > 1)
        } finally {
            output.allow(WritesAfterStart)
            streams.release()
        }
    }

    private fun player(
        wav: ByteArray,
        ranges: CopyOnWriteArrayList<Requested>,
        output: PermitOutput,
    ) = SharedTrackStreams({ Files() }, memory(wav, ranges), { output })

    private fun take(id: String) = TrackEntity(
        id = id,
        projectId = "song",
        contentHash = "hash-$id",
        duration = FrameCount.toLong(),
    )

    private class Files : SharedProjects {
        override suspend fun list(): List<SharedProject> = error("unused")

        override suspend fun imageUrl(id: String): String = error("unused")

        override suspend fun playback(id: String): List<SharedPlayback> = error("unused")

        override suspend fun files(id: String): List<SharedTake> = listOf(
            SharedTake("take-a", "hash-take-a", 1L, "https://example/a"),
            SharedTake("take-b", "hash-take-b", 1L, "https://example/b"),
        )
    }

    private fun memory(
        wav: ByteArray,
        ranges: CopyOnWriteArrayList<Requested>,
        fail: (Long) -> IOException? = { null },
    ): MixSource = object : MixSource {
        override fun open(url: String, start: Long, endInclusive: Long): InputStream {
            val failure = fail(start)
            ranges.add(Requested(url, start, endInclusive))
            if (failure != null) throw failure
            val from = start.toInt().coerceAtMost(wav.size)
            val to = (endInclusive + 1).toInt().coerceAtMost(wav.size)
            return ByteArrayInputStream(wav.copyOfRange(from, to))
        }
    }

    private fun waitUntil(probe: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WaitMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return true
            Thread.sleep(PollMs)
        }
        return probe()
    }

    private fun stable(ranges: CopyOnWriteArrayList<Requested>): Boolean {
        val first = ranges.size
        Thread.sleep(PollMs)
        return ranges.size == first
    }

    private fun pieceStarts(ranges: List<Requested>): List<Long> =
        ranges.filter { it.start >= HeaderBytes }.map { it.start }

    private fun wavFrames(frameCount: Int): ByteArray {
        val file = File.createTempFile("track", ".wav")
        writeConstantPcm16Wav(file, 1, frameCount = frameCount, sampleRateHz = SampleRate)
        return file.readBytes().also { file.delete() }
    }

    private fun pieceStart(index: Int): Long = HeaderBytes + index.toLong() * PieceBytes

    private class Requested(val url: String, val start: Long, val endInclusive: Long)

    private class PermitOutput : PcmOutput {
        private val permits = AtomicInteger(0)

        fun allow(count: Int) {
            permits.addAndGet(count)
        }

        override fun start(sampleRateHz: Int, channelCount: Int) = Unit

        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            while (permits.get() == 0) Thread.sleep(PollMs)
            permits.decrementAndGet()
            return length
        }

        override fun pause() = Unit

        override fun play() = Unit

        override fun release() = Unit
    }

    private companion object {
        const val HeaderBytes = 44L
        const val PieceBytes = 4_000L
        const val AheadPieces = 2
        const val FrameCount = 80_000
        const val SampleRate = 1_000
        const val SeekMs = 10_500L
        const val TargetStart = 20_044L
        const val TargetEnd = 24_043L
        const val ExactPcmOffset = 21_044L
        const val WritesAfterStart = 8
        const val WaitMs = 3_000L
        const val PollMs = 10L
    }
}
