package com.georgv.audioworkstation.server

import java.io.File
import java.io.InputStream

/**
 * The only audio storage. Every object is one file in [directory], named by its SHA-256 hex.
 */
class ContentAddressedStorage(val directory: File) {
    init {
        check(directory.mkdirs() || directory.isDirectory) { "Storage directory was not created." }
    }

    fun file(storageKey: String): File {
        requireContentHash(storageKey)
        val stored = File(directory, storageKey)
        val directoryPath = directory.canonicalFile
        check(stored.canonicalFile.parentFile == directoryPath) { "Storage key left the storage directory." }
        return stored
    }

    fun put(storageKey: String, body: InputStream) {
        val destination = file(storageKey)
        if (destination.isFile && Sha256Hex.ofFile(destination) == storageKey) {
            val incoming = Sha256Hex.ofStream(body)
            if (incoming != storageKey) {
                throw OnlineFailure(400, "stored bytes do not match the content hash")
            }
            return
        }
        val written = Sha256Hex.writeAndHash(destination, body)
        if (written != storageKey) {
            destination.delete()
            throw OnlineFailure(400, "stored bytes do not match the content hash")
        }
    }
}

private val contentHashPattern = Regex("^[0-9a-f]{64}$")

fun requireContentHash(value: String) {
    if (!contentHashPattern.matches(value)) {
        throw OnlineFailure(400, "content hash must be sha-256 hex")
    }
}
