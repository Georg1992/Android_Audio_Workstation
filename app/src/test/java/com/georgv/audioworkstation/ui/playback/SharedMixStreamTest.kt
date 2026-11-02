package com.georgv.audioworkstation.ui.playback

import com.georgv.audioworkstation.core.audio.WavStreamHeader
import com.georgv.audioworkstation.core.audio.writeConstantPcm16Wav
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedMixStreamTest {
    @Test
    fun playStartsFromASmallBufferAndLeavesTheRestUnread() {
        val wav = mixWav()
        val release = CountDownLatch(1)
        val read = AtomicInteger(0)
        val source = object : MixSource {
            override fun open(url: String, offset: Long): InputStream =
                LimitedStream(wav, HeaderBytes + StartBytes, read, release)
        }
        val output = FakeOutput()
        val stream = SharedMixStream(source, output, aheadBytes = AheadBytes, startBytes = StartBytes)
        val returned = CountDownLatch(1)
        val worker = Thread {
            stream.play("https://mix.example/song")
            returned.countDown()
        }
        worker.start()
        try {
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertTrue(output.started)
            assertTrue(output.played)
            assertEquals(HeaderBytes + StartBytes, read.get())
            assertTrue(wav.size > read.get())
        } finally {
            stream.release()
            worker.join(JoinMs)
        }
    }

    @Test
    fun readAheadStaysInsideTheWindowWhilePlaybackIsSlow() {
        val wav = largeWav()
        val read = AtomicInteger(0)
        val source = object : MixSource {
            override fun open(url: String, offset: Long): InputStream = CountingStream(wav, offset, read)
        }
        val output = PermitOutput()
        val stream = SharedMixStream(source, output, aheadBytes = AheadBytes, startBytes = StartBytes)
        val returned = CountDownLatch(1)
        val worker = Thread {
            stream.play("https://mix.example/song")
            returned.countDown()
        }
        worker.start()
        try {
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertTrue(waitUntil { read.get() >= HeaderBytes + StartBytes && stable(read) })
            val parked = read.get()
            val window = HeaderBytes + AheadBytes + ChunkBytes * 3
            assertTrue(parked <= window)
            assertTrue(parked < wav.size / 2)
            output.allow(WritesAfterStart)
            assertTrue(waitUntil { read.get() > parked })
            assertTrue(read.get() < wav.size)
        } finally {
            output.allow(WritesAfterStart)
            stream.release()
            worker.join(JoinMs)
        }
    }

    @Test
    fun seekRequestsThePcmOffsetInTheMix() {
        val wav = mixWav()
        val info = WavStreamHeader.read(ByteArrayInputStream(wav)) ?: error("wav header")
        val frames = SeekMs.toLong() * info.sampleRateHz / MsPerSecond
        val expected = info.dataOffset + frames * info.blockAlign
        assertEquals(1044L, expected)
        val offsets = CopyOnWriteArrayList<Long>()
        val seen = CountDownLatch(1)
        val source = object : MixSource {
            override fun open(url: String, offset: Long): InputStream {
                offsets += offset
                if (offset == expected) seen.countDown()
                val prefix = if (offset == 0L) wav.copyOf(HeaderBytes + StartBytes) else ByteArray(0)
                return GatedStream(prefix, CountDownLatch(1), AtomicBoolean(false))
            }
        }
        val stream = SharedMixStream(source, FakeOutput(), aheadBytes = AheadBytes, startBytes = StartBytes)
        val returned = CountDownLatch(1)
        val worker = Thread {
            stream.play("https://mix.example/song")
            returned.countDown()
        }
        worker.start()
        try {
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            stream.seekTo(SeekMs)
            assertTrue(seen.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(0L, expected), offsets)
        } finally {
            stream.release()
            worker.join(JoinMs)
        }
    }

    @Test
    fun stopClearsPlayingAndPosition() {
        val wav = mixWav()
        val release = CountDownLatch(1)
        val source = object : MixSource {
            override fun open(url: String, offset: Long): InputStream =
                GatedStream(wav.copyOf(HeaderBytes + PcmLead), release, AtomicBoolean(false))
        }
        val stream = SharedMixStream(source, FakeOutput(), aheadBytes = AheadBytes, startBytes = StartBytes)
        val returned = CountDownLatch(1)
        val worker = Thread {
            stream.play("https://mix.example/song")
            returned.countDown()
        }
        worker.start()
        try {
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertTrue(waitUntil { stream.transport().positionMs > 0 })
            stream.stop()
            val transport = stream.transport()
            assertFalse(transport.playing)
            assertEquals(0, transport.positionMs)
        } finally {
            stream.release()
            worker.join(JoinMs)
        }
    }

    @Test
    fun closeUnblocksABlockedRead() {
        val release = CountDownLatch(1)
        val stream = GatedStream(ByteArray(0), release, AtomicBoolean(false))
        val failed = AtomicBoolean(false)
        val worker = Thread {
            try {
                stream.read()
            } catch (closed: IOException) {
                failed.set(closed.message == "closed")
            }
        }
        worker.start()
        assertTrue(waitUntil { worker.state == Thread.State.WAITING || worker.state == Thread.State.TIMED_WAITING })
        stream.close()
        worker.join(JoinMs)
        assertTrue(failed.get())
    }

    @Test
    fun rangedResponseThatIsNotPartialThrows() {
        val wav = mixWav()
        val ranges = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mix") { exchange ->
            ranges += exchange.requestHeaders.getFirst("Range")
            exchange.sendResponseHeaders(200, wav.size.toLong())
            exchange.responseBody.use { it.write(wav) }
        }
        server.start()
        try {
            val source = UrlMixSource()
            val url = "http://127.0.0.1:${server.address.port}/mix"
            source.open(url, 0L).close()
            val failure = runCatching { source.open(url, 1044L) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("bytes=1044-", ranges.last())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun partialRangeStartsAtTheRequestedByte() {
        val wav = mixWav()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mix") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range == null) {
                exchange.sendResponseHeaders(200, wav.size.toLong())
                exchange.responseBody.use { it.write(wav) }
            } else {
                val start = range.removePrefix("bytes=").substringBefore("-").toInt()
                val body = wav.copyOfRange(start, wav.size)
                val end = wav.size - 1
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${wav.size}")
                exchange.sendResponseHeaders(206, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        try {
            val source = UrlMixSource()
            val url = "http://127.0.0.1:${server.address.port}/mix"
            val bytes = source.open(url, 1044L).use { it.readNBytes(2) }
            assertEquals(2, bytes.size)
        } finally {
            server.stop(0)
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

    private fun stable(read: AtomicInteger): Boolean {
        val first = read.get()
        Thread.sleep(PollMs)
        return read.get() == first
    }

    private class CountingStream(
        private val wav: ByteArray,
        offset: Long,
        private val read: AtomicInteger,
    ) : InputStream() {
        private var index = offset.toInt()

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            if (count < 0) return -1
            return one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (index >= wav.size) return -1
            val count = minOf(len, wav.size - index)
            wav.copyInto(buffer, off, index, index + count)
            index += count
            read.addAndGet(count)
            return count
        }
    }

    private class LimitedStream(
        private val wav: ByteArray,
        private val limit: Int,
        private val read: AtomicInteger,
        private val release: CountDownLatch,
    ) : InputStream() {
        private var index = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            if (count < 0) return -1
            return one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (index >= limit) {
                release.await()
                throw IOException("closed")
            }
            val count = minOf(len, limit - index, wav.size - index)
            wav.copyInto(buffer, off, index, index + count)
            index += count
            read.set(index)
            return count
        }

        override fun close() {
            release.countDown()
        }
    }

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

        override fun release() {
            allow(WritesAfterStart)
        }
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

    private class GatedStream(
        private val prefix: ByteArray,
        private val release: CountDownLatch,
        private val bodyEntered: AtomicBoolean,
    ) : InputStream() {
        private var index = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            if (count < 0) return -1
            return one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (index < prefix.size) {
                val count = minOf(len, prefix.size - index)
                prefix.copyInto(buffer, off, index, index + count)
                index += count
                return count
            }
            bodyEntered.set(true)
            release.await()
            throw IOException("closed")
        }

        override fun close() {
            release.countDown()
        }
    }

    private fun mixWav(): ByteArray = wavFrames(2_000)

    private fun largeWav(): ByteArray = wavFrames(80_000)

    private fun wavFrames(frameCount: Int): ByteArray {
        val file = File.createTempFile("mix", ".wav")
        writeConstantPcm16Wav(file, 1, frameCount = frameCount, sampleRateHz = 1_000)
        return file.readBytes().also { file.delete() }
    }

    private companion object {
        const val HeaderBytes = 44
        const val PcmLead = 800
        const val StartBytes = 256
        const val AheadBytes = 1_024
        const val ChunkBytes = 4_096
        const val WritesAfterStart = 8
        const val SeekMs = 500
        const val MsPerSecond = 1_000
        const val JoinMs = 1_000L
        const val WaitMs = 2_000L
        const val PollMs = 10L
    }
}
