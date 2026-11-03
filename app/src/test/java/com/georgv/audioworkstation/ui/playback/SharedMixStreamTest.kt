package com.georgv.audioworkstation.ui.playback

import com.georgv.audioworkstation.core.audio.WavStreamHeader
import com.georgv.audioworkstation.core.audio.writeConstantPcm16Wav
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedMixStreamTest {
    @Test
    fun playStartsFromTheFirstPiece() {
        val wav = largeWav()
        val ranges = CopyOnWriteArrayList<Requested>()
        val release = CountDownLatch(1)
        val source = memory(wav, ranges, blockAt = pieceStart(1), release = release)
        val output = FakeOutput()
        val stream = SharedMixStream(source, output, aheadPieces = AheadPieces)
        try {
            stream.play(Url)
            Thread.sleep(SettleMs)
            assertTrue(output.started)
            assertTrue(output.played)
            assertEquals(listOf(0L, pieceStart(0)), ranges.map { it.start })
            assertEquals(pieceEnd(0), ranges[1].endInclusive)
            assertTrue(ranges.size < wav.size)
        } finally {
            release.countDown()
            stream.release()
        }
    }

    @Test
    fun readAheadStaysInsideASmallStretch() {
        val wav = largeWav()
        val ranges = CopyOnWriteArrayList<Requested>()
        val source = memory(wav, ranges)
        val output = PermitOutput()
        val stream = SharedMixStream(source, output, aheadPieces = AheadPieces)
        try {
            stream.play(Url)
            assertTrue(waitUntil { pieceStarts(ranges).size == AheadPieces + 1 && stable(ranges) })
            val parked = pieceStarts(ranges).size
            assertTrue(parked < PieceCount / 2)
            output.allow(WritesAfterStart)
            assertTrue(waitUntil { pieceStarts(ranges).size > parked })
            assertTrue(pieceStarts(ranges).size < PieceCount)
        } finally {
            output.allow(WritesAfterStart)
            stream.release()
        }
    }

    @Test
    fun seekStartsAtTheNewSample() {
        val wav = largeWav()
        val info = WavStreamHeader.read(ByteArrayInputStream(wav)) ?: error("wav header")
        val bytes = MixPieces.pieceBytes(info.sampleRateHz, info.blockAlign)
        val pcm = MixPieces.pcmByte(info, SeekMs)
        val window = MixPieces.span(info, bytes, MixPieces.indexAt(pcm, bytes))
        check(window != null)
        assertEquals(TargetStart, window.fileStart)
        assertEquals(TargetEnd, window.fileEndInclusive)
        val ranges = CopyOnWriteArrayList<Requested>()
        val output = PermitOutput()
        val stream = SharedMixStream(memory(wav, ranges), output, aheadPieces = AheadPieces)
        try {
            stream.play(Url)
            assertTrue(waitUntil { pieceStarts(ranges) == FirstPieces && stable(ranges) })
            stream.seekTo(SeekMs)
            assertEquals(SeekMs, stream.transport().positionMs)
            assertTrue(waitUntil { ranges.any { it.start == TargetStart } })
            val next = ranges.first { it.start == TargetStart }
            assertEquals(TargetEnd, next.endInclusive)
            assertFalse(pieceStarts(ranges).contains(ExactPcmOffset))
            output.allow(WritesAfterStart)
            assertTrue(waitUntil { stream.transport().positionMs > SeekMs })
            assertTrue(stream.transport().playing)
        } finally {
            output.allow(WritesAfterStart)
            stream.release()
        }
    }

    @Test
    fun seekToAStoredPieceStartsWithoutFetchingItAgain() {
        val wav = largeWav()
        val ranges = CopyOnWriteArrayList<Requested>()
        val blocked = AtomicBoolean(false)
        val source = object : MixSource {
            override fun open(url: String, start: Long, endInclusive: Long): InputStream {
                if (blocked.get()) {
                    ranges.add(Requested(start, endInclusive))
                    throw IOException("closed")
                }
                ranges.add(Requested(start, endInclusive))
                return slice(wav, start, endInclusive)
            }
        }
        val output = PermitOutput()
        val stream = SharedMixStream(source, output, aheadPieces = AheadPieces)
        try {
            stream.play(Url)
            assertTrue(waitUntil { ranges.count { it.start == StoredPiece } == 1 && stable(ranges) })
            blocked.set(true)
            stream.seekTo(StoredMs)
            output.allow(WritesAfterStart)
            assertTrue(waitUntil { stream.transport().positionMs > StoredMs })
            assertEquals(1, ranges.count { it.start == StoredPiece })
        } finally {
            blocked.set(false)
            output.allow(WritesAfterStart)
            stream.release()
        }
    }

    @Test
    fun seekInsideTheOpenPieceStartsWithoutThatRequest() {
        val wav = largeWav()
        val gate = CountDownLatch(1)
        val source = object : MixSource {
            override fun open(url: String, start: Long, endInclusive: Long): InputStream {
                if (start == InsideFile) gate.await()
                return slice(wav, start, endInclusive)
            }
        }
        val output = PermitOutput()
        val stream = SharedMixStream(source, output, aheadPieces = AheadPieces)
        try {
            stream.play(Url)
            assertTrue(waitUntil { output.attempts.get() > 0 })
            stream.seekTo(InsideMs)
            output.allow(WritesAfterStart)
            assertTrue(waitUntil { stream.transport().positionMs > InsideMs })
        } finally {
            gate.countDown()
            output.allow(WritesAfterStart)
            stream.release()
        }
    }

    @Test
    fun slowConnectionKeepsBufferedAudioAndResumesTheSamePiece() {
        val wav = largeWav()
        val ranges = CopyOnWriteArrayList<Requested>()
        val failures = AtomicInteger(0)
        val online = AtomicBoolean(false)
        val source = memory(wav, ranges) { start ->
            if (start == pieceStart(1) && !online.get()) {
                failures.incrementAndGet()
                UnknownHostException("Unable to resolve host")
            } else {
                null
            }
        }
        val output = PermitOutput()
        val stream = SharedMixStream(source, output, aheadPieces = AheadPieces)
        try {
            stream.play(Url)
            assertTrue(waitUntil { failures.get() > 0 })
            assertTrue(stream.pullFailure() == null)
            assertTrue(stream.transport().playing)
            output.allow(1)
            val stalled = waitForFailure(stream)
            assertTrue(stalled is UnknownHostException)
            assertTrue(stream.transport().playing)
            assertTrue(stream.pullFailure() == null)
            online.set(true)
            output.allow(WritesAfterStart)
            assertTrue(waitUntil { stream.transport().positionMs > FirstPieceMs })
            assertTrue(stream.transport().playing)
            assertTrue(ranges.filter { it.start == pieceStart(1) }.size > 1)
        } finally {
            online.set(true)
            output.allow(WritesAfterStart)
            stream.release()
        }
    }

    @Test
    fun playThrowsTheConnectionErrorWhenTheFirstPieceCannotStart() {
        val source = object : MixSource {
            override fun open(url: String, start: Long, endInclusive: Long): InputStream {
                throw UnknownHostException("Unable to resolve host")
            }
        }
        val stream = SharedMixStream(source, FakeOutput(), startWaitMs = StartWaitMs)
        try {
            val failure = runCatching { stream.play(Url) }.exceptionOrNull()
            assertTrue(failure is UnknownHostException)
        } finally {
            stream.release()
        }
    }

    @Test
    fun seekClosesABlockedPieceRead() {
        val wav = largeWav()
        val held = AtomicReference<HoldStream>()
        val source = object : MixSource {
            override fun open(url: String, start: Long, endInclusive: Long): InputStream {
                if (start == pieceStart(1) && held.get() == null) {
                    val block = HoldStream()
                    held.set(block)
                    return block
                }
                return slice(wav, start, endInclusive)
            }
        }
        val stream = SharedMixStream(source, FakeOutput(), aheadPieces = AheadPieces)
        try {
            stream.play(Url)
            val block = waitForHold(held)
            assertTrue(block.entered.await(WaitMs, java.util.concurrent.TimeUnit.MILLISECONDS))
            stream.seekTo(SeekMs)
            assertTrue(waitUntil { block.closed.get() })
        } finally {
            held.get()?.close()
            stream.release()
        }
    }

    @Test
    fun stopClearsPlayingAndPosition() {
        val wav = largeWav()
        val release = CountDownLatch(1)
        val stream = SharedMixStream(
            memory(wav, CopyOnWriteArrayList(), blockAt = pieceStart(1), release = release),
            FakeOutput(),
            aheadPieces = AheadPieces,
        )
        try {
            stream.play(Url)
            assertTrue(waitUntil { stream.transport().positionMs > 0 })
            stream.stop()
            val transport = stream.transport()
            assertFalse(transport.playing)
            assertEquals(0, transport.positionMs)
        } finally {
            release.countDown()
            stream.release()
        }
    }

    @Test
    fun rangedResponseThatIsNotPartialThrows() {
        val wav = largeWav()
        val ranges = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mix") { exchange ->
            ranges.add(exchange.requestHeaders.getFirst("Range"))
            exchange.sendResponseHeaders(200, wav.size.toLong())
            exchange.responseBody.use { it.write(wav) }
        }
        server.start()
        try {
            val failure = runCatching { UrlMixSource().open(url(server), RangeStart, RangeEnd) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("bytes=$RangeStart-$RangeEnd", ranges.last())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun partialRangeIsTheClosedPiece() {
        val wav = largeWav()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mix") { exchange ->
            val header = exchange.requestHeaders.getFirst("Range")
            val start = header.removePrefix("bytes=").substringBefore("-").toInt()
            val end = header.substringAfter("-").toInt()
            val body = wav.copyOfRange(start, end + 1)
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${wav.size}")
            exchange.sendResponseHeaders(206, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val bytes = UrlMixSource().open(url(server), RangeStart, RangeEnd).use { it.readBytes() }
            assertEquals(wav.copyOfRange(RangeStart.toInt(), RangeEnd.toInt() + 1).asList(), bytes.asList())
        } finally {
            server.stop(0)
        }
    }

    private fun waitForFailure(stream: SharedMixStream): Exception? {
        val deadline = System.currentTimeMillis() + WaitMs
        while (System.currentTimeMillis() < deadline) {
            val failure = stream.pullFailure()
            if (failure != null) return failure
            Thread.sleep(PollMs)
        }
        return stream.pullFailure()
    }

    private fun waitForHold(held: AtomicReference<HoldStream>): HoldStream {
        val deadline = System.currentTimeMillis() + WaitMs
        while (System.currentTimeMillis() < deadline) {
            val block = held.get()
            if (block != null) return block
            Thread.sleep(PollMs)
        }
        return held.get() ?: error("piece read did not start")
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

    private fun memory(
        wav: ByteArray,
        ranges: CopyOnWriteArrayList<Requested>,
        blockAt: Long = Long.MAX_VALUE,
        release: CountDownLatch = CountDownLatch(0),
        fail: (Long) -> IOException? = { null },
    ): MixSource = object : MixSource {
        override fun open(url: String, start: Long, endInclusive: Long): InputStream {
            val failure = fail(start)
            if (failure != null) {
                ranges.add(Requested(start, endInclusive))
                throw failure
            }
            if (start >= blockAt) {
                release.await()
                throw IOException("closed")
            }
            ranges.add(Requested(start, endInclusive))
            return slice(wav, start, endInclusive)
        }
    }

    private fun slice(wav: ByteArray, start: Long, endInclusive: Long): InputStream =
        ByteArrayInputStream(sliceBytes(wav, start, endInclusive))

    private fun sliceBytes(wav: ByteArray, start: Long, endInclusive: Long): ByteArray {
        val from = start.toInt().coerceAtMost(wav.size)
        val to = (endInclusive + 1).toInt().coerceAtMost(wav.size)
        return wav.copyOfRange(from, to)
    }

    private fun url(server: HttpServer): String = "http://127.0.0.1:${server.address.port}/mix"

    private fun largeWav(): ByteArray = wavFrames(FrameCount)

    private fun wavFrames(frameCount: Int): ByteArray {
        val file = File.createTempFile("mix", ".wav")
        writeConstantPcm16Wav(file, 1, frameCount = frameCount, sampleRateHz = SampleRate)
        return file.readBytes().also { file.delete() }
    }

    private fun pieceStart(index: Int): Long = HeaderBytes + index.toLong() * PieceBytes

    private fun pieceEnd(index: Int): Long = pieceStart(index) + PieceBytes - 1

    private class Requested(val start: Long, val endInclusive: Long)

    private class PermitOutput : PcmOutput {
        private val permits = AtomicInteger(0)
        val attempts = AtomicInteger(0)

        fun allow(count: Int) {
            permits.addAndGet(count)
        }

        override fun start(sampleRateHz: Int, channelCount: Int) = Unit

        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            attempts.incrementAndGet()
            while (permits.get() == 0) Thread.sleep(PollMs)
            permits.decrementAndGet()
            return length
        }

        override fun pause() = Unit

        override fun play() = Unit

        override fun release() = Unit
    }

    private class FakeOutput : PcmOutput {
        var started = false
        var played = false

        override fun start(sampleRateHz: Int, channelCount: Int) {
            started = true
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int): Int = length

        override fun pause() = Unit

        override fun play() {
            played = true
        }

        override fun release() = Unit
    }

    private class HoldStream : InputStream() {
        val entered = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        private val release = CountDownLatch(1)

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            if (count < 0) return -1
            return one[0].toInt() and ByteMask
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            entered.countDown()
            release.await()
            throw IOException("closed")
        }

        override fun close() {
            closed.set(true)
            release.countDown()
        }
    }

    private companion object {
        const val Url = "https://mix.example/song"
        const val HeaderBytes = 44L
        const val PieceBytes = 4_000L
        const val AheadPieces = 2
        const val FrameCount = 80_000
        const val PieceCount = 40
        const val SampleRate = 1_000
        const val SeekMs = 10_500
        const val StoredMs = 4_500
        const val InsideMs = 500
        const val FirstPieceMs = 2_000
        const val TargetStart = 20_044L
        const val TargetEnd = 24_043L
        const val ExactPcmOffset = 21_044L
        const val StoredPiece = 8_044L
        const val InsideFile = 1_044L
        const val RangeStart = 1_044L
        const val RangeEnd = 2_047L
        const val WritesAfterStart = 8
        const val StartWaitMs = 500L
        const val WaitMs = 3_000L
        const val PollMs = 10L
        const val SettleMs = 40L
        const val ByteMask = 0xFF
        val FirstPieces = listOf(44L, 4_044L, 8_044L)
    }
}
