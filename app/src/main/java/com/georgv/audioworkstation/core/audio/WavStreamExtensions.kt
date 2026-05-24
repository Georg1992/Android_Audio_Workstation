package com.georgv.audioworkstation.core.audio

import java.io.InputStream

internal fun InputStream.readExactly(count: Int): ByteArray? {
    val buffer = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(buffer, read, count - read)
        if (n < 0) return null
        read += n
    }
    return buffer
}

internal fun InputStream.skipExactly(count: Long): Long? {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped <= 0) {
            if (read() < 0) return null
            remaining -= 1
        } else {
            remaining -= skipped
        }
    }
    return count
}

internal fun ByteArray.startsWith(prefix: ByteArray, offset: Int): Boolean {
    if (offset + prefix.size > size) return false
    return prefix.indices.all { this[offset + it] == prefix[it] }
}

internal fun ByteArray.readLittleEndianInt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.readLittleEndianShort(offset: Int): Short =
    (((this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8))).toShort()
