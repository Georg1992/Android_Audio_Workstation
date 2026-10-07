package com.georgv.audioworkstation.server

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

object Sha256Hex {
    fun ofBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun ofFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> update(digest, input) }
        return digest.digest().toHex()
    }

    fun ofStream(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, input)
        return digest.digest().toHex()
    }

    fun writeAndHash(destination: File, input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        destination.outputStream().use { output ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
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

internal fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }

internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex length must be even" }
    return ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
