package com.georgv.audioworkstation.ui.playback

import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal interface MixSource {
    fun open(url: String, start: Long, endInclusive: Long): InputStream
}

internal class UrlMixSource : MixSource {
    override fun open(url: String, start: Long, endInclusive: Long): InputStream {
        val connection = connect(url, start, endInclusive)
        var keep = false
        try {
            val status = connection.responseCode
            if (status != Partial) error("mix stream status $status")
            keep = true
            return ClosingStream(connection, connection.inputStream)
        } finally {
            if (!keep) connection.disconnect()
        }
    }

    private fun connect(url: String, start: Long, endInclusive: Long): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = ConnectTimeoutMs
        connection.readTimeout = ReadTimeoutMs
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Range", "bytes=$start-$endInclusive")
        return connection
    }

    private companion object {
        const val Partial = 206
        const val ConnectTimeoutMs = 15_000
        const val ReadTimeoutMs = 30_000
    }
}

/** Reads a mix that is already stored on the phone. */
internal class FileMixSource : MixSource {
    override fun open(url: String, start: Long, endInclusive: Long): InputStream {
        val file = File(url)
        val length = endInclusive - start + 1L
        if (!file.isFile) error("mix is not aac")
        if (length <= 0L || start < 0L || start + length > file.length()) error("mix is not aac")
        val input = FileInputStream(file)
        input.channel.position(start)
        return BoundedStream(input, length)
    }
}

private class BoundedStream(
    input: FileInputStream,
    private var remaining: Long,
) : FilterInputStream(input) {
    override fun read(): Int {
        if (remaining <= 0L) return -1
        val value = super.read()
        if (value >= 0) remaining -= 1L
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0L) return -1
        val count = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) remaining -= count.toLong()
        return count
    }
}

private class ClosingStream(
    private val connection: HttpURLConnection,
    stream: InputStream,
) : FilterInputStream(stream) {
    override fun close() {
        try {
            super.close()
        } finally {
            connection.disconnect()
        }
    }
}
