package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTrackVisibilityTest {

    @Test
    fun `optimistic recording appends when track id is absent`() {
        val base = listOf(track("a", 0))
        val optimistic = track("new", 1).copy(isRecording = true)

        val visible = visibleTracksWithRecordingOptimistic(base, null, optimistic)

        assertEquals(2, visible.size)
        assertEquals("new", visible.last().id)
        assertTrue(visible.last().isRecording)
    }

    @Test
    fun `optimistic recording replaces existing track with same id`() {
        val base = listOf(track("a", 0, duration = 5_000L))
        val optimistic =
            track("a", 0).copy(
                isRecording = true,
                duration = null,
                timelineStartOffsetMs = 1_000L,
            )

        val visible = visibleTracksWithRecordingOptimistic(base, null, optimistic)

        assertEquals(1, visible.size)
        assertEquals("a", visible.single().id)
        assertTrue(visible.single().isRecording)
        assertEquals(null, visible.single().duration)
        assertEquals(1_000L, visible.single().timelineStartOffsetMs)
    }

    @Test
    fun `track lane shows global playhead when clip is visible`() {
        assertTrue(trackLaneShowsGlobalPlayhead(showWaveforms = true, hasTimelineClip = true))
        assertEquals(
            false,
            trackLaneShowsGlobalPlayhead(showWaveforms = false, hasTimelineClip = true),
        )
        assertEquals(
            false,
            trackLaneShowsGlobalPlayhead(showWaveforms = true, hasTimelineClip = false),
        )
    }

    private fun track(id: String, position: Int, duration: Long? = null) =
        TrackEntity(
            id = id,
            projectId = "p",
            position = position,
            name = id,
            duration = duration,
        )
}
