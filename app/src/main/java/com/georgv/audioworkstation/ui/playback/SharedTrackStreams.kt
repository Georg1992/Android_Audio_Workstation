package com.georgv.audioworkstation.ui.playback

import com.georgv.audioworkstation.core.session.ProjectStreamPlayer
import com.georgv.audioworkstation.core.track.streamsSharedTake
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.online.share.SharedProjects
import kotlinx.coroutines.CancellationException

/**
 * Plays each shared take with the same piece player as the shared mix.
 * One [SharedMixStream] per take. A local WAV is not opened here.
 */
internal class SharedTrackStreams(
    private val projects: () -> SharedProjects,
    private val source: MixSource = UrlMixSource(),
    private val output: () -> PcmOutput = { AudioTrackPcmOutput() },
    private val open: (MixSource, PcmOutput) -> SharedMixStream = { mixSource, pcm ->
        SharedMixStream(mixSource, pcm)
    },
) : ProjectStreamPlayer {
    private val streams = LinkedHashMap<String, SharedMixStream>()
    private var clock = false

    override suspend fun play(
        projectId: String,
        tracks: List<TrackEntity>,
        positionMs: Long,
        streamClock: Boolean,
    ) {
        val streamed = tracks.filter { it.streamsSharedTake() }
        if (streamed.isEmpty()) {
            release()
            return
        }
        val urls = projects().files(projectId).associate { it.trackId to it.downloadUrl }
        val keep = streamed.map { it.id }.toSet()
        streams.keys.filter { it !in keep }.forEach { id -> streams.remove(id)?.release() }
        val position = positionMs.coerceIn(0L, MaxPositionMs).toInt()
        try {
            streamed.forEach { track -> startOne(track, urlFor(urls, track.id), position) }
        } catch (cancel: CancellationException) {
            release()
            throw cancel
        } catch (error: Exception) {
            release()
            throw error
        }
        clock = streamClock
    }

    override fun sameTracks(tracks: List<TrackEntity>): Boolean {
        val ids = tracks.filter { it.streamsSharedTake() }.map { it.id }.toSet()
        return ids.isNotEmpty() && ids == streams.keys
    }

    override fun seekTo(positionMs: Long) {
        if (streams.isEmpty()) return
        val position = positionMs.coerceIn(0L, MaxPositionMs).toInt()
        streams.values.forEach { stream -> stream.seekTo(position) }
    }

    override fun useStreamClock(enabled: Boolean) {
        clock = enabled && streams.isNotEmpty()
    }

    override fun stop() {
        clock = false
        streams.values.forEach { stream -> stream.stop() }
    }

    override fun positionMs(): Long =
        streams.values.firstOrNull()?.transport()?.positionMs?.toLong() ?: 0L

    override fun ownsClock(): Boolean = clock

    override fun consumeClock(onFailure: (Exception) -> Unit): Boolean {
        val failure = pullFailure()
        if (failure != null) onFailure(failure)
        if (!clock) return false
        if (streams.values.any { stream -> stream.transport().playing }) return false
        release()
        return true
    }

    override fun release() {
        clock = false
        val openStreams = streams.values.toList()
        streams.clear()
        openStreams.forEach { stream -> stream.release() }
    }

    private fun startOne(track: TrackEntity, url: String, position: Int) {
        val existing = streams[track.id]
        if (existing == null) {
            val created = open(source, output())
            streams[track.id] = created
            created.play(url)
            if (position > 0) created.seekTo(position)
        } else {
            existing.play(url)
            existing.seekTo(position)
        }
    }

    private fun urlFor(urls: Map<String, String>, trackId: String): String =
        urls[trackId] ?: error("shared take is missing")

    private fun pullFailure(): Exception? {
        for (stream in streams.values) {
            val failure = stream.pullFailure()
            if (failure != null) return failure
        }
        return null
    }

    private companion object {
        const val MaxPositionMs = 86_400_000L
    }
}
