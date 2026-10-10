package com.georgv.audioworkstation.online.share

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal interface ShareObjectTransport {
    fun put(url: String, headers: Map<String, String>, file: File)

    fun download(url: String, destination: File)
}

internal class UrlConnectionShareObjects : ShareObjectTransport {
    override fun put(url: String, headers: Map<String, String>, file: File) {
        val connection = open(url, "PUT")
        try {
            connection.doOutput = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setFixedLengthStreamingMode(file.length())
            connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output) } }
            val status = connection.responseCode
            if (status !in 200..299) error("upload failed: $status")
        } finally {
            connection.disconnect()
        }
    }

    override fun download(url: String, destination: File) {
        if (destination.exists()) error("local audio already exists")
        val parent = destination.parentFile ?: error("audio path has no directory")
        val connection = open(url, "GET")
        val temp = File(parent, "${destination.name}.download")
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("download failed: $status")
            temp.outputStream().use { output -> connection.inputStream.use { it.copyTo(output) } }
            if (destination.exists()) error("local audio already exists")
            if (!temp.renameTo(destination)) error("could not store downloaded audio")
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        return connection
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 300_000
    }
}
