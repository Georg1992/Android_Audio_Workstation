package com.georgv.audioworkstation.online

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

object Sha256Hex {
    fun ofFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> update(digest, input) }
        return digest.digest().toHex()
    }

    private fun update(digest: MessageDigest, input: InputStream) {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }

    private const val STREAM_BUFFER_BYTES = 8192
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
