package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHotJoinTimelineTest {

    private fun loopTrack(
        timelineStartOffsetMs: Long,
        loopStartMs: Long = 0L,
        loopEndMs: Long = 5_000L,
        durationMs: Long = 20_000L,
    ): TrackEntity =
        TrackEntity(
            id = "loop",
            projectId = "p",
            wavFilePath = "loop.wav",
            duration = durationMs,
            timelineStartOffsetMs = timelineStartOffsetMs,
            isLoop = true,
            loopStartMs = loopStartMs,
            loopEndMs = loopEndMs,
        )

    private fun oneShotTrack(
        timelineStartOffsetMs: Long,
        durationMs: Long,
    ): TrackEntity =
        TrackEntity(
            id = "one",
            projectId = "p",
            wavFilePath = "one.wav",
            duration = durationMs,
            timelineStartOffsetMs = timelineStartOffsetMs,
        )

    @Test
    fun `example B loop at 30s transport 10s hot joins and is audible at loop phase`() {
        val track = loopTrack(timelineStartOffsetMs = 30_000L)
        assertTrue(shouldHotJoinTrackAtTransport(track, transportMs = 10_000L))
        assertTrue(
            isLaneAudibleAtPlayhead(
                playheadMs = 10_000L,
                clipStartMs = 30_000L,
                clipDurationMs = 20_000L,
                loopEnabled = true,
            ),
        )
        assertEquals(
            0L,
            laneSourceReadOffsetMs(
                playheadMs = 10_000L,
                clipStartMs = 30_000L,
                loopEnabled = true,
                loopSourceStartMs = 0L,
                loopSourceEndMs = 5_000L,
            ),
        )
    }

    @Test
    fun `example C loop at 30s transport 35s hot joins with wrapped source phase`() {
        val track = loopTrack(timelineStartOffsetMs = 30_000L, loopStartMs = 0L, loopEndMs = 5_000L)
        assertTrue(shouldHotJoinTrackAtTransport(track, transportMs = 35_000L))
        assertTrue(
            isLaneAudibleAtPlayhead(
                playheadMs = 35_000L,
                clipStartMs = 30_000L,
                clipDurationMs = 20_000L,
                loopEnabled = true,
            ),
        )
        assertEquals(
            0L,
            laneSourceReadOffsetMs(
                playheadMs = 35_000L,
                clipStartMs = 30_000L,
                loopEnabled = true,
                loopSourceStartMs = 0L,
                loopSourceEndMs = 5_000L,
            ),
        )
    }

    @Test
    fun `example E one shot at 15s transport 20s hot joins at source 5s`() {
        val track = oneShotTrack(timelineStartOffsetMs = 15_000L, durationMs = 10_000L)
        assertTrue(shouldHotJoinTrackAtTransport(track, transportMs = 20_000L))
        assertEquals(5_000L, laneSourceOffsetMs(playheadMs = 20_000L, clipStartMs = 15_000L))
    }

    @Test
    fun `loop track past base duration still hot joins`() {
        val track = loopTrack(timelineStartOffsetMs = 30_000L, durationMs = 5_000L)
        assertTrue(shouldHotJoinTrackAtTransport(track, transportMs = 40_000L))
    }

    @Test
    fun `one shot past clip end does not hot join`() {
        val track = oneShotTrack(timelineStartOffsetMs = 10_000L, durationMs = 5_000L)
        assertFalse(shouldHotJoinTrackAtTransport(track, transportMs = 20_000L))
    }

    @Test
    fun `toHotJoinLaneSpec forwards loop region metadata`() {
        val track =
            loopTrack(
                timelineStartOffsetMs = 30_000L,
                loopStartMs = 2_000L,
                loopEndMs = 8_000L,
            )
        val spec = track.toHotJoinLaneSpec()
        assertEquals(30_000L, spec.timelineClipStartMs)
        assertTrue(spec.loopEnabled)
        assertEquals(2_000L, spec.loopSourceStartMs)
        assertEquals(8_000L, spec.loopSourceEndMs)
    }
}
