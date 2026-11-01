package com.georgv.audioworkstation.ui.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.georgv.audioworkstation.core.audio.readWavPcmFileInfo
import com.georgv.audioworkstation.core.util.logWarning
import java.io.RandomAccessFile

/**
 * Plays a 16-bit PCM WAV from a buffer already in memory, so a scrub moves the
 * playhead without waiting on a disk seek.
 */
class MemoryPcmPreview {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private var pcm = ByteArray(0)
    private var sampleRate = 0
    private var channels = 0
    private var blockAlign = 0
    private var durationMs = 0
    private var readByte = 0
    private var seekMs: Int? = null
    private var generation = 0
    private var playing = false
    private var ended = false
    private var closed = true
    private var track: AudioTrack? = null
    private var writer: Thread? = null

    fun load(path: String): Boolean {
        val loaded = readBytes(path) ?: return false
        release()
        return synchronized(lock) {
            pcm = loaded.bytes
            sampleRate = loaded.sampleRate
            channels = loaded.channels
            blockAlign = loaded.blockAlign
            durationMs = loaded.durationMs
            readByte = 0
            seekMs = null
            generation = 0
            playing = false
            ended = false
            closed = false
            if (!openTrackLocked()) {
                pcm = ByteArray(0)
                durationMs = 0
                closed = true
                return false
            }
            writer = Thread(::writerLoop, THREAD_NAME).also { thread ->
                thread.isDaemon = true
                thread.start()
            }
            true
        }
    }

    fun play() {
        synchronized(lock) {
            if (pcm.isEmpty() || track == null) return
            if (ended || readByte >= pcm.size) {
                seekMs = 0
                ended = false
            }
            playing = true
            lock.notifyAll()
        }
    }

    fun stop() {
        synchronized(lock) {
            playing = false
            ended = false
            seekMs = 0
            generation += 1
            lock.notifyAll()
        }
    }

    fun seekTo(positionMs: Int) {
        synchronized(lock) {
            if (pcm.isEmpty()) return
            seekMs = positionMs.coerceIn(0, durationMs)
            lock.notifyAll()
        }
    }

    fun transport(): MemoryPreviewTransport {
        synchronized(lock) {
            val pending = seekMs
            val position = if (pending != null) pending else msAt(readByte)
            return MemoryPreviewTransport(
                playing = playing && !ended,
                positionMs = position,
                durationMs = durationMs,
                ended = ended,
            )
        }
    }

    fun release() {
        val thread = synchronized(lock) {
            closed = true
            playing = false
            lock.notifyAll()
            writer.also { writer = null }
        }
        thread?.join(JOIN_MS)
        synchronized(lock) {
            track?.release()
            track = null
            pcm = ByteArray(0)
            durationMs = 0
            readByte = 0
            seekMs = null
        }
    }

    private fun writerLoop() {
        while (true) {
            val step = synchronized(lock) { readStepLocked() } ?: return
            val audio = track ?: return
            if (step.kind == StepKind.Idle) {
                pauseForWork()
                continue
            }
            if (step.kind == StepKind.Seek) {
                seekTrack(audio)
                if (step.playing) {
                    val follow = synchronized(lock) { readStepLocked() }
                    if (follow != null && follow.kind == StepKind.Write) writeAudio(audio, follow)
                }
                continue
            }
            writeAudio(audio, step)
        }
    }

    private fun readStepLocked(): Step? {
        if (closed) return null
        val pending = seekMs
        if (pending != null) {
            seekMs = null
            readByte = bytesAt(pending)
            generation += 1
            return Step(StepKind.Seek, playing = playing, serial = generation)
        }
        if (!playing || readByte >= pcm.size) {
            if (playing && readByte >= pcm.size && pcm.isNotEmpty()) {
                playing = false
                ended = true
            }
            return Step(StepKind.Idle)
        }
        val frames = (sampleRate / CHUNKS_PER_SECOND).coerceAtLeast(1)
        val chunk = frames * blockAlign.coerceAtLeast(1)
        val length = minOf(chunk, pcm.size - readByte)
        return Step(StepKind.Write, offset = readByte, length = length, serial = generation, playing = playing)
    }

    private fun pauseForWork() {
        synchronized(lock) {
            if (closed || seekMs != null) return
            try {
                lock.wait(POLL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                closed = true
            }
        }
    }

    private fun seekTrack(audio: AudioTrack) {
        try {
            audio.pause()
            audio.flush()
        } catch (error: IllegalStateException) {
            logWarning(TAG, "seek failed", error)
        }
    }

    private fun writeAudio(audio: AudioTrack, step: Step) {
        val written = audio.write(pcm, step.offset, step.length, AudioTrack.WRITE_NON_BLOCKING)
        var resume = false
        val starved = synchronized(lock) {
            if (closed || step.serial != generation || written < 0) return@synchronized false
            if (written > 0 && readByte == step.offset) {
                readByte = step.offset + written
            }
            if (playing && readByte >= pcm.size && pcm.isNotEmpty()) {
                playing = false
                ended = true
            }
            resume = playing && written > 0
            written == 0
        }
        if (resume && audio.playState != AudioTrack.PLAYSTATE_PLAYING) audio.play()
        if (starved) pauseForWork()
    }

    private fun readBytes(path: String): LoadedPcm? {
        val info = readWavPcmFileInfo(path) ?: return null
        val fits = info.isSupportedProjectPcm && info.dataSize > 0L && info.dataSize <= Int.MAX_VALUE
        if (!fits) return null
        return try {
            val bytes = ByteArray(info.dataSize.toInt())
            RandomAccessFile(path, "r").use { file ->
                file.seek(info.dataOffset)
                file.readFully(bytes)
            }
            LoadedPcm(
                bytes = bytes,
                sampleRate = info.sampleRateHz,
                channels = info.channelCount,
                blockAlign = info.blockAlign,
                durationMs = info.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        } catch (error: Exception) {
            logWarning(TAG, "load failed", error)
            null
        }
    }

    private fun msAt(offset: Int): Int {
        if (sampleRate <= 0 || blockAlign <= 0) return 0
        val frames = offset.coerceAtLeast(0) / blockAlign
        return ((frames.toLong() * MS_PER_SECOND) / sampleRate).toInt()
    }

    private fun bytesAt(positionMs: Int): Int {
        if (sampleRate <= 0 || blockAlign <= 0 || pcm.isEmpty()) return 0
        val frames = positionMs.toLong().coerceAtLeast(0L) * sampleRate / MS_PER_SECOND
        val frameCount = pcm.size / blockAlign
        return (frames.coerceAtMost(frameCount.toLong()) * blockAlign).toInt()
    }

    private fun openTrackLocked(): Boolean {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return false
        return try {
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(minBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                return false
            }
            track = created
            true
        } catch (error: IllegalArgumentException) {
            logWarning(TAG, "audio track failed", error)
            false
        }
    }

    private companion object {
        const val TAG = "MemoryPcmPreview"
        const val THREAD_NAME = "card-preview"
        const val POLL_MS = 10L
        const val JOIN_MS = 1000L
        const val MS_PER_SECOND = 1000L
        const val CHUNKS_PER_SECOND = 100
    }
}

data class MemoryPreviewTransport(
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val ended: Boolean = false,
)

private data class LoadedPcm(
    val bytes: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val blockAlign: Int,
    val durationMs: Int,
)

private enum class StepKind { Idle, Seek, Write }

private data class Step(
    val kind: StepKind,
    val playing: Boolean = false,
    val offset: Int = 0,
    val length: Int = 0,
    val serial: Int = 0,
)
