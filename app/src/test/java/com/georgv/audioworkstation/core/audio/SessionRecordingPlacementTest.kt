package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRecordingPlacementTest {
    @Test
    fun `record only uses capture placement ms`() {
        assertEquals(
            187L,
            SessionRecordingPlacement.resolveTimelineStartOffsetMs(
                firstSampleTransportPositionMs = 187L,
                sessionPerceivedPlaybackOffsetMs = RecordingStopSnapshot.SessionPerceivedPlaybackOffsetUnset,
                overdubBackingArmMs = null,
            ),
        )
    }

    @Test
    fun `overdub subtracts perceived playback offset from capture placement`() {
        assertEquals(
            95L,
            SessionRecordingPlacement.resolveTimelineStartOffsetMs(
                firstSampleTransportPositionMs = 157L,
                sessionPerceivedPlaybackOffsetMs = 62L,
                overdubBackingArmMs = 0L,
            ),
        )
    }

    @Test
    fun `overdub never places before backing arm ms`() {
        assertEquals(
            500L,
            SessionRecordingPlacement.resolveTimelineStartOffsetMs(
                firstSampleTransportPositionMs = 600L,
                sessionPerceivedPlaybackOffsetMs = 241L,
                overdubBackingArmMs = 500L,
            ),
        )
    }
}
