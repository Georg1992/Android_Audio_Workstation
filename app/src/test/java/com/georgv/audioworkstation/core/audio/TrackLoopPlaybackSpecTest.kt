package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.core.audio.laneSourceReadOffsetMs
import com.georgv.audioworkstation.ui.components.sessionTimelineEndMsForPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLoopPlaybackSpecTest {

  private val project = ProjectEntity(id = "project-1", sampleRate = 48_000)

  @Test
  fun `toMultiPlaybackSpec maps loop fields for looped track`() {
    val track =
        TrackEntity(
            id = "loop",
            projectId = "project-1",
            wavFilePath = "/tmp/loop.wav",
            gain = 80f,
            duration = 20_000L,
            isLoop = true,
            loopStartMs = 2_000L,
            loopEndMs = 8_000L,
        )

    val lane = project.toMultiPlaybackSpec(listOf(track))!!.lanes.single()

    assertTrue(lane.loopEnabled)
    assertEquals(2_000L, lane.loopSourceStartMs)
    assertEquals(8_000L, lane.loopSourceEndMs)
    assertEquals(0.8f, lane.gain)
  }

  @Test
  fun `looped track timelineClipStartMs uses placement offset only`() {
    val track =
        TrackEntity(
            id = "loop",
            projectId = "project-1",
            wavFilePath = "/tmp/loop.wav",
            duration = 15_000L,
            timelineStartOffsetMs = 5_000L,
            isLoop = true,
            loopStartMs = 3_000L,
            loopEndMs = 10_000L,
        )

    val lane = project.toMultiPlaybackSpec(listOf(track))!!.lanes.single()

    assertEquals(5_000L, lane.timelineClipStartMs)
  }

  @Test
  fun `looped track timelineClipDurationMs uses full source duration`() {
    val track =
        TrackEntity(
            id = "loop",
            projectId = "project-1",
            wavFilePath = "/tmp/loop.wav",
            duration = 20_000L,
            isLoop = true,
            loopStartMs = 2_000L,
            loopEndMs = 9_000L,
        )

    val lane = project.toMultiPlaybackSpec(listOf(track))!!.lanes.single()

    assertEquals(20_000L, lane.timelineClipDurationMs)
  }

  @Test
  fun `looped track lane source read offset wraps inside loop region`() {
    val track =
        TrackEntity(
            id = "loop",
            projectId = "project-1",
            wavFilePath = "/tmp/loop.wav",
            duration = 20_000L,
            timelineStartOffsetMs = 5_000L,
            isLoop = true,
            loopStartMs = 2_000L,
            loopEndMs = 9_000L,
        )

    val lane = project.toMultiPlaybackSpec(listOf(track))!!.lanes.single()

    assertEquals(
        4_000L,
        laneSourceReadOffsetMs(
            playheadMs = 7_000L,
            clipStartMs = lane.timelineClipStartMs,
            loopEnabled = lane.loopEnabled,
            loopSourceStartMs = lane.loopSourceStartMs,
            loopSourceEndMs = lane.loopSourceEndMs,
        ),
    )
  }

  @Test
  fun `sessionTimelineEndMsForPlayback is zero when any track loops`() {
    val tracks =
        listOf(
            TrackEntity(
                id = "a",
                projectId = "project-1",
                wavFilePath = "a.wav",
                duration = 10_000L,
                isLoop = false,
            ),
            TrackEntity(
                id = "b",
                projectId = "project-1",
                wavFilePath = "b.wav",
                duration = 30_000L,
                isLoop = true,
            ),
        )

    assertEquals(0L, sessionTimelineEndMsForPlayback(tracks))
  }

  @Test
  fun `non-loop tracks keep session timeline end for playback`() {
    val tracks =
        listOf(
            TrackEntity(
                id = "a",
                projectId = "project-1",
                wavFilePath = "a.wav",
                duration = 10_000L,
                timelineStartOffsetMs = 2_000L,
            ),
            TrackEntity(
                id = "b",
                projectId = "project-1",
                wavFilePath = "b.wav",
                duration = 5_000L,
                timelineStartOffsetMs = 1_000L,
            ),
        )

        assertEquals(12_000L, sessionTimelineEndMsForPlayback(tracks))
  }

  @Test
  fun `toMultiPlaybackSpec maps loop fields for every looped lane`() {
    val trackA =
        TrackEntity(
            id = "a",
            projectId = "project-1",
            wavFilePath = "/tmp/a.wav",
            duration = 20_000L,
            isLoop = true,
            loopStartMs = 1_000L,
            loopEndMs = 4_000L,
        )
    val trackB =
        TrackEntity(
            id = "b",
            projectId = "project-1",
            wavFilePath = "/tmp/b.wav",
            duration = 15_000L,
            isLoop = true,
            loopStartMs = 2_500L,
            loopEndMs = 7_500L,
        )

    val spec = project.toMultiPlaybackSpec(listOf(trackA, trackB))!!

    assertEquals(0L, sessionTimelineEndMsForPlayback(listOf(trackA, trackB)))
    assertEquals(2, spec.lanes.size)
    val laneA = spec.lanes[0]
    val laneB = spec.lanes[1]
    assertTrue(laneA.loopEnabled)
    assertTrue(laneB.loopEnabled)
    assertEquals(1_000L, laneA.loopSourceStartMs)
    assertEquals(4_000L, laneA.loopSourceEndMs)
    assertEquals(2_500L, laneB.loopSourceStartMs)
    assertEquals(7_500L, laneB.loopSourceEndMs)
  }

  @Test
  fun `toMultiPlaybackSpec leaves loop fields off for one-shot tracks`() {
    val track =
        TrackEntity(
            id = "a",
            projectId = "project-1",
            wavFilePath = "/tmp/a.wav",
            duration = 12_000L,
        )

    val lane = project.toMultiPlaybackSpec(listOf(track))!!.lanes.single()

    assertFalse(lane.loopEnabled)
    assertEquals(0L, lane.loopSourceStartMs)
    assertEquals(12_000L, lane.loopSourceEndMs)
  }
}
