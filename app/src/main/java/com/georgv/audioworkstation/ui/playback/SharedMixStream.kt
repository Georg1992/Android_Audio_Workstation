package com.georgv.audioworkstation.ui.playback

import java.util.concurrent.TimeUnit

/**
 * Plays one shared mix. Playback starts from the first piece. The master mix
 * keeps every piece for that playback. [restartAt] is the stream form of the
 * project seek: the source is placed at that time, then the output is started
 * again with [PcmOutput.start].
 */
internal class SharedMixStream(
    private val source: MixSource = UrlMixSource(),
    private val output: PcmOutput = AudioTrackPcmOutput(),
    private val aheadPieces: Int = MixPieces.AheadPieces,
    private val startWaitMs: Long = StartWaitMs,
    private val keepSong: Boolean = false,
) {
    private val buffer = MixPieceBuffer(aheadPieces + 1)
    private val state = MixPlayState(buffer)
    private val reader = MixPieceReader(source, state)
    private var writer: Thread? = null

    fun play(url: String) {
        val resumed = state.resume(url)
        if (resumed != null) {
            resumed.stream?.close()
            buffer.kick()
            return
        }
        begin(url)
    }

    fun stop() {
        val open = state.retarget(0L, audible = false)
        open?.close()
        buffer.kick()
    }

    /** Pause the output and keep the heard playhead. The card stays on this mix. */
    fun hold() {
        if (state.snapshot() == null) return
        val heard = synchronized(state.lock) { state.audiblePcm }
        val open = state.retarget(heard, audible = false)
        open?.close()
        buffer.kick()
    }

    fun restartAt(positionMs: Int) {
        val snap = state.snapshot() ?: return
        val pcm = MixPieces.pcmByte(snap.info, positionMs)
        val open = state.retarget(pcm, audible = true)
        open?.close()
        if (!keepSong) buffer.retain(MixPieces.indexAt(pcm, snap.pieceBytes))
        buffer.kick()
    }

    fun seekTo(positionMs: Int) = restartAt(positionMs)

    fun transport(): StreamTransport = state.transport()

    fun pullFailure(): Exception? = state.pullFailure()

    fun release() {
        stopSession()
        output.release()
    }

    private fun begin(url: String) {
        stopSession()
        buffer.reset()
        val gate = state.open(url)
        val thread = Thread({
            MixSession(state, reader, buffer, output, aheadPieces, keepSong).run()
        }, ThreadName)
        thread.isDaemon = true
        writer = thread
        thread.start()
        val opened = try {
            gate.await(startWaitMs, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val failure = state.startError()
        if (failure != null) {
            stopSession()
            throw failure
        }
        if (!opened) {
            stopSession()
            error("mix stream did not start")
        }
    }

    private fun stopSession() {
        val open = state.closeSession()
        open?.close()
        buffer.close()
        val thread = writer
        writer = null
        if (thread != null && thread != Thread.currentThread()) thread.join(JoinMs)
    }

    private companion object {
        const val ThreadName = "shared-mix"
        const val StartWaitMs = 5_000L
        const val JoinMs = 1_000L
    }
}

data class StreamTransport(
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)
