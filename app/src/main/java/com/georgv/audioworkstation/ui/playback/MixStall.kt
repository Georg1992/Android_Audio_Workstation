package com.georgv.audioworkstation.ui.playback

import com.georgv.audioworkstation.core.util.logWarning

internal fun MixPlayState.noteStall(error: Exception) {
    synchronized(lock) {
        val gate = start
        if (gate != null && gate.latch.count > 0L) gate.failure = error
        val idle = !holding && buffer.size() == 0
        val dry = playing && !closed && idle
        if (dry && !stallSent) {
            stall = error
            logWarning(Tag, "mix piece stalled", error)
        }
    }
}

internal fun MixPlayState.noteReady() {
    synchronized(lock) {
        stall = null
        stallSent = false
        val gate = start
        if (gate != null && gate.latch.count > 0L) gate.failure = null
    }
}

internal fun MixPlayState.pullFailure(): Exception? {
    synchronized(lock) {
        val found = stall
        stall = null
        if (found != null) stallSent = true
        return found
    }
}

internal fun MixPlayState.failHard(error: Exception) {
    synchronized(lock) {
        broken = true
        playing = false
        stall = error
        stallSent = false
        val gate = start
        if (gate != null) {
            gate.failure = error
            gate.latch.countDown()
        }
        lock.notifyAll()
    }
}

internal fun MixPlayState.startError(): Exception? = synchronized(lock) { start?.failure }

internal fun MixPlayState.finish() {
    synchronized(lock) {
        if (closed) return
        playing = false
        lock.notifyAll()
    }
}

private const val Tag = "SharedMixStream"
