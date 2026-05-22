package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStartPositionTest {

    @Test
    fun `PlaybackSpec defaults startPositionMs to zero`() {
        val spec = PlaybackSpec(sampleRate = 48_000, wavFilePath = "a.wav", gain = 1f)
        assertEquals(0L, spec.startPositionMs)
    }

    @Test
    fun `MultiPlaybackSpec defaults startPositionMs to zero`() {
        val spec =
            MultiPlaybackSpec(
                sampleRate = 48_000,
                lanes = listOf(TrackPlaybackLane("a", "a.wav", 1f)),
            )
        assertEquals(0L, spec.startPositionMs)
    }

    @Test
    fun `toMultiPlaybackSpec preserves default start position`() {
        val project = ProjectEntity(id = "p", sampleRate = 48_000)
        val tracks = listOf(TrackEntity(id = "a", projectId = "p", wavFilePath = "a.wav"))

        val spec = project.toMultiPlaybackSpec(tracks)

        assertEquals(0L, spec?.startPositionMs)
    }

    @Test
    fun `copy applies non-zero start position for native playback`() {
        val project = ProjectEntity(id = "p", sampleRate = 48_000)
        val tracks = listOf(TrackEntity(id = "a", projectId = "p", wavFilePath = "a.wav"))

        val spec =
            project.toMultiPlaybackSpec(tracks)?.copy(startPositionMs = 1_000L)

        assertEquals(1_000L, spec?.startPositionMs)
        assertEquals(48_000, spec?.sampleRate)
    }

    @Test
    fun `playbackStartFrame converts ms using project sample rate`() {
        assertEquals(0L, playbackStartFrame(sampleRateHz = 48_000, startPositionMs = 0L))
        assertEquals(48_000L, playbackStartFrame(sampleRateHz = 48_000, startPositionMs = 1_000L))
        assertEquals(44_100L, playbackStartFrame(sampleRateHz = 44_100, startPositionMs = 1_000L))
    }

    @Test
    fun `negative start position is rejected`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PlaybackSpec(sampleRate = 48_000, wavFilePath = "a.wav", gain = 1f, startPositionMs = -1L)
        }
    }
}

/** Mirrors native [playbackStartFrameFromMs] for unit tests. */
internal fun playbackStartFrame(sampleRateHz: Int, startPositionMs: Long): Long =
    if (startPositionMs <= 0L || sampleRateHz <= 0) {
        0L
    } else {
        sampleRateHz.toLong() * startPositionMs / 1000L
    }
