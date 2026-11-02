package com.georgv.audioworkstation.ui.playback

import java.io.InputStream

internal class MixReader(
    private val ring: PcmRing,
    private val hooks: MixHooks,
) {
    fun read(serial: Int) {
        val buffer = ByteArray(ChunkBytes)
        while (accept(buffer, serial)) Unit
    }

    private fun accept(buffer: ByteArray, serial: Int): Boolean {
        val step = hooks.next(serial) ?: return false
        if (step.reopen) {
            ring.clear()
            hooks.reopenAt(step.offset)
            return true
        }
        if (!step.read) return true
        val current = hooks.look().stream ?: return true
        val count = hooks.read(current, buffer) ?: return true
        if (count < 0) {
            ring.put(buffer, 0, PcmRing.Closed)
            hooks.clearStream(current)
            return true
        }
        return store(buffer, count, serial)
    }

    private fun store(buffer: ByteArray, count: Int, serial: Int): Boolean {
        var offset = 0
        while (offset < count) {
            val look = hooks.look()
            val stop = look.closed || look.generation != serial || look.seeking
            if (stop) return true
            val wrote = ring.put(buffer, offset, count - offset)
            if (wrote == PcmRing.Closed) return false
            if (wrote == 0) return true
            offset += wrote
        }
        return true
    }

    private companion object {
        const val ChunkBytes = 4096
    }
}

internal class MixHooks(
    val next: (Int) -> MixStep?,
    val reopenAt: (Long) -> Unit,
    val look: () -> MixLook,
    val clearStream: (InputStream) -> Unit,
    val read: (InputStream, ByteArray) -> Int?,
)

internal class MixLook(
    val closed: Boolean,
    val generation: Int,
    val seeking: Boolean,
    val stream: InputStream?,
)

internal class MixStep(val reopen: Boolean, val read: Boolean, val offset: Long)
