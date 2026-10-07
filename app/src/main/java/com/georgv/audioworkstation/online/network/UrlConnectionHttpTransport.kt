package com.georgv.audioworkstation.online.network

import java.net.HttpURLConnection
import java.net.URL

class UrlConnectionHttpTransport(private val baseUrl: String) : HttpTransport {
    override fun exchange(call: HttpCall): HttpResult {
        val connection = open(call)
        try {
            val writeBody = call.writeBody
            if (writeBody != null) {
                connection.doOutput = true
                if (call.contentType != null) connection.setRequestProperty("Content-Type", call.contentType)
                connection.setFixedLengthStreamingMode(call.contentLength)
                connection.outputStream.use { writeBody(it) }
            }
            return read(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(call: HttpCall): HttpURLConnection {
        val connection = URL(baseUrl + call.path).openConnection() as HttpURLConnection
        connection.requestMethod = call.method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        if (call.token != null) connection.setRequestProperty("Authorization", "Bearer ${call.token}")
        return connection
    }

    private fun read(connection: HttpURLConnection): HttpResult {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        return HttpResult(status = status, body = text)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
