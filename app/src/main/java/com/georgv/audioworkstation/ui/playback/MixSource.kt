package com.georgv.audioworkstation.ui.playback

import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal interface MixSource {
    fun open(url: String, offset: Long): InputStream
}

internal class UrlMixSource : MixSource {
    override fun open(url: String, offset: Long): InputStream {
        val connection = connect(url, offset)
        var keep = false
        try {
            val status = connection.responseCode
            val accepted = if (offset > 0L) status == Partial else status == Ok
            if (!accepted) error("mix stream status $status")
            keep = true
            return ClosingStream(connection, connection.inputStream)
        } finally {
            if (!keep) connection.disconnect()
        }
    }

    private fun connect(url: String, offset: Long): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = ConnectTimeoutMs
        connection.readTimeout = ReadTimeoutMs
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
        return connection
    }

    private companion object {
        const val Ok = 200
        const val Partial = 206
        const val ConnectTimeoutMs = 15_000
        const val ReadTimeoutMs = 30_000
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
