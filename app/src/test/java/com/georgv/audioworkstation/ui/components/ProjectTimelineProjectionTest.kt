package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.core.audio.waveform.WaveformPeaks
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.timelineClipLayout
import com.georgv.audioworkstation.ui.components.timelineLaneLocalLayoutDurationMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTimelineProjectionTest {

    @Test
    fun `empty project timeline duration is zero regardless of playhead`() {
        val projection =
            buildIdleProjection(playheadPositionMs = 45_000L)

        assertEquals(0L, projection.baseTimelineDurationMs)
        assertEquals(0L, projection.visibleTimelineDurationMs)
        assertTrue(projection.clips.isEmpty())
    }

    @Test
    fun `idle visible timeline ignores playhead beyond base`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                ),
            )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                selectedTrackIds = tracks.map { it.id }.toSet(),
                activeRecording = null,
                playheadPositionMs = 25_000L,
                extendVisibleTimelineForAllLoopedPlayback = false,
                extendVisibleTimelineForRecording = false,
            )

        assertEquals(10_000L, projection.baseTimelineDurationMs)
        assertEquals(10_000L, projection.visibleTimelineDurationMs)
    }

    @Test
    fun `all-looped playback extends visible timeline when playhead passes base`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 10_000L,
                    isLoop = true,
                ),
            )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                selectedTrackIds = tracks.map { it.id }.toSet(),
                activeRecording = null,
                playheadPositionMs = 25_000L,
                extendVisibleTimelineForAllLoopedPlayback = true,
                extendVisibleTimelineForRecording = false,
            )

        assertEquals(10_000L, projection.baseTimelineDurationMs)
        assertEquals(25_000L, projection.visibleTimelineDurationMs)
    }

    @Test
    fun `stopping playback restores visible timeline to base`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 10_000L,
                    isLoop = true,
                ),
            )
        val playing =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                selectedTrackIds = tracks.map { it.id }.toSet(),
                activeRecording = null,
                playheadPositionMs = 25_000L,
                extendVisibleTimelineForAllLoopedPlayback = true,
                extendVisibleTimelineForRecording = false,
            )
        val idle =
            buildIdleProjection(
                tracks = tracks,
                playheadPositionMs = 25_000L,
            )

        assertEquals(25_000L, playing.visibleTimelineDurationMs)
        assertEquals(10_000L, idle.visibleTimelineDurationMs)
        assertEquals(idle.baseTimelineDurationMs, idle.visibleTimelineDurationMs)
    }

    @Test
    fun `active recording participates in timeline and base calculation`() {
        val projection =
            buildProjectTimelineProjection(
                tracks = emptyList(),
                waveformStatesByTrackId = emptyMap(),
                selectedTrackIds = setOf("rec"),
                activeRecording =
                    ActiveRecordingTimelineClip(
                        trackId = "rec",
                        startOffsetMs = 0L,
                        elapsedMs = 12_000L,
                    ),
                playheadPositionMs = 12_000L,
                extendVisibleTimelineForAllLoopedPlayback = false,
                extendVisibleTimelineForRecording = true,
            )

        val clip = projection.clipsByLaneId["rec"]
        assertNotNull(clip)
        assertEquals(0L, clip!!.startOffsetMs)
        assertEquals(12_000L, clip.durationMs)
        assertTrue(clip.isActiveRecording)
        assertTrue(clip.isTimelineBase)
        assertEquals(0L, projection.baseTimelineDurationMs)
        assertEquals(12_000L, projection.laneLayoutDurationMs)
        assertEquals(12_000L, projection.visibleTimelineDurationMs)
    }

    @Test
    fun `first take on empty project has lane layout duration for clip rendering`() {
        val projection =
            buildProjectTimelineProjection(
                tracks = emptyList(),
                waveformStatesByTrackId = emptyMap(),
                selectedTrackIds = setOf("rec"),
                activeRecording =
                    ActiveRecordingTimelineClip(
                        trackId = "rec",
                        startOffsetMs = 0L,
                        elapsedMs = 0L,
                    ),
                playheadPositionMs = 0L,
                extendVisibleTimelineForAllLoopedPlayback = false,
                extendVisibleTimelineForRecording = true,
            )

        val clip = projection.clipsByLaneId["rec"]
        assertNotNull(clip)
        assertEquals(1L, clip!!.durationMs)
        assertEquals(0L, projection.baseTimelineDurationMs)
        assertEquals(1L, projection.laneLayoutDurationMs)
        assertNotNull(timelineClipLayout(clip, projection.laneLayoutDurationMs))
    }

    @Test
    fun `recording from middle expands visible timeline to start plus elapsed`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "existing",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 5_000L,
                    timelineStartOffsetMs = 0L,
                ),
            )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                selectedTrackIds = setOf("existing", "rec"),
                activeRecording =
                    ActiveRecordingTimelineClip(
                        trackId = "rec",
                        startOffsetMs = 30_000L,
                        elapsedMs = 8_000L,
                    ),
                playheadPositionMs = 38_000L,
                extendVisibleTimelineForAllLoopedPlayback = false,
                extendVisibleTimelineForRecording = true,
            )

        assertEquals(5_000L, projection.baseTimelineDurationMs)
        assertEquals(38_000L, projection.laneLayoutDurationMs)
        assertEquals(38_000L, projection.visibleTimelineDurationMs)
        val recordingClip = projection.clipsByLaneId["rec"]!!
        assertTrue(recordingClip.isTimelineBase)
        assertNotNull(timelineClipLayout(recordingClip, projection.laneLayoutDurationMs))
    }

    @Test
    fun `recording past prior timeline end grows visible duration`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                ),
            )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                selectedTrackIds = setOf("a", "rec"),
                activeRecording =
                    ActiveRecordingTimelineClip(
                        trackId = "rec",
                        startOffsetMs = 0L,
                        elapsedMs = 15_000L,
                    ),
                playheadPositionMs = 15_000L,
                extendVisibleTimelineForAllLoopedPlayback = false,
                extendVisibleTimelineForRecording = true,
            )

        assertEquals(10_000L, projection.baseTimelineDurationMs)
        assertEquals(15_000L, projection.laneLayoutDurationMs)
        assertEquals(15_000L, projection.visibleTimelineDurationMs)
        val recordingClip = projection.clipsByLaneId["rec"]!!
        assertTrue(recordingClip.isTimelineBase)
        assertNotNull(timelineClipLayout(recordingClip, projection.laneLayoutDurationMs))
    }

    @Test
    fun `punch recording merges active flag into persisted clip with loop bounds`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "target",
                    projectId = "p",
                    wavFilePath = "target.wav",
                    duration = 20_000L,
                    isRecording = true,
                    isLoop = true,
                    loopStartMs = 6_000L,
                    loopEndMs = 12_000L,
                ),
            )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId =
                    mapOf("target" to WaveformState.Ready(WaveformPeaks.Placeholder)),
                selectedTrackIds = setOf("target"),
                activeRecording =
                    ActiveRecordingTimelineClip(
                        trackId = "target",
                        startOffsetMs = 0L,
                        elapsedMs = 3_000L,
                    ),
                playheadPositionMs = 3_000L,
                extendVisibleTimelineForAllLoopedPlayback = false,
                extendVisibleTimelineForRecording = true,
            )

        val clip = projection.clipsByLaneId["target"]!!
        assertTrue(clip.isActiveRecording)
        assertEquals(20_000L, clip.durationMs)
        assertEquals(6_000L, clip.effectiveStartMs)
        assertEquals(12_000L, clip.effectiveEndMs)
        assertTrue(clip.waveformState is WaveformState.Ready)
    }

    @Test
    fun `loop bounds survive playback projection with extended visible timeline`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 20_000L,
                    isLoop = true,
                    loopStartMs = 6_000L,
                    loopEndMs = 12_000L,
                ),
            )
        val projection =
            buildProjectTimelineProjection(
                tracks = tracks,
                waveformStatesByTrackId = emptyMap(),
                selectedTrackIds = tracks.map { it.id }.toSet(),
                activeRecording = null,
                playheadPositionMs = 25_000L,
                extendVisibleTimelineForAllLoopedPlayback = true,
                extendVisibleTimelineForRecording = false,
            )

        val clip = projection.clipsByLaneId["loop"]!!
        assertEquals(6_000L, clip.effectiveStartMs)
        assertEquals(12_000L, clip.effectiveEndMs)
        assertEquals(25_000L, projection.visibleTimelineDurationMs)
        assertEquals(20_000L, projection.baseTimelineDurationMs)
    }

    @Test
    fun `timelineClipsWithActiveRecording keeps persisted clip when lane already exists`() {
        val persisted =
            TimelineClip(
                clipId = "a",
                laneId = "a",
                startOffsetMs = 0L,
                durationMs = 10_000L,
                waveformState = WaveformState.Ready(WaveformPeaks.Placeholder),
                isTimelineBase = true,
                formattedDuration = "0:10",
                isLoop = true,
                effectiveStartMs = 2_000L,
                effectiveEndMs = 8_000L,
            )
        val recording =
            TimelineClip(
                clipId = "a",
                laneId = "a",
                startOffsetMs = 0L,
                durationMs = 1L,
                waveformState = WaveformState.NoWaveform,
                isTimelineBase = true,
                formattedDuration = "0:00",
                isActiveRecording = true,
                effectiveStartMs = 0L,
                effectiveEndMs = 1L,
            )

        val merged = timelineClipsWithActiveRecording(listOf(persisted), recording).single()

        assertTrue(merged.isActiveRecording)
        assertEquals(10_000L, merged.durationMs)
        assertEquals(2_000L, merged.effectiveStartMs)
        assertEquals(8_000L, merged.effectiveEndMs)
        assertTrue(merged.waveformState is WaveformState.Ready)
    }

    @Test
    fun `base track is furthest clip end not longest duration only`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "long",
                    projectId = "p",
                    wavFilePath = "long.wav",
                    duration = 20_000L,
                    timelineStartOffsetMs = 0L,
                ),
                TrackEntity(
                    id = "late",
                    projectId = "p",
                    wavFilePath = "late.wav",
                    duration = 3_000L,
                    timelineStartOffsetMs = 30_000L,
                ),
            )
        val projection = buildIdleProjection(tracks = tracks)

        assertEquals(33_000L, projection.baseTimelineDurationMs)
        assertEquals(33_000L, projection.visibleTimelineDurationMs)
        assertTrue(projection.clipsByLaneId["late"]!!.isTimelineBase)
        assertEquals(false, projection.clipsByLaneId["long"]!!.isTimelineBase)
    }

    @Test
    fun `looped track with shortened loop end keeps full duration base timeline`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 20_000L,
                    isLoop = true,
                    loopStartMs = 0L,
                    loopEndMs = 12_000L,
                ),
                TrackEntity(
                    id = "longer",
                    projectId = "p",
                    wavFilePath = "longer.wav",
                    duration = 15_000L,
                ),
            )
        val projection = buildIdleProjection(tracks = tracks)

        assertEquals(20_000L, projection.baseTimelineDurationMs)
        assertEquals(20_000L, projection.visibleTimelineDurationMs)
        assertTrue(projection.clipsByLaneId["loop"]!!.isTimelineBase)
        assertFalse(projection.clipsByLaneId["longer"]!!.isTimelineBase)
    }

    @Test
    fun `session timeline end uses full placement not loop end`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 20_000L,
                    isLoop = true,
                    loopEndMs = 12_000L,
                ),
            )

        assertEquals(20_000L, sessionTimelineEndMsForTracks(tracks))
    }

    @Test
    fun `mixdown timeline end matches idle global ruler base duration`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "short",
                    projectId = "p",
                    wavFilePath = "short.wav",
                    duration = 8_000L,
                    importStatus = TrackImportStatus.READY,
                ),
                TrackEntity(
                    id = "late",
                    projectId = "p",
                    wavFilePath = "late.wav",
                    duration = 10_000L,
                    timelineStartOffsetMs = 12_000L,
                    importStatus = TrackImportStatus.READY,
                ),
            )
        val projection = buildIdleProjection(tracks = tracks)

        assertEquals(22_000L, projection.baseTimelineDurationMs)
        assertEquals(projection.baseTimelineDurationMs, mixdownTimelineEndMs(tracks, tracks.map { it.id }.toSet()))
        assertEquals(MixdownTimelineStartMs, 0L)
    }

    @Test
    fun `base timeline and base track ignore unselected lanes`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "short",
                    projectId = "p",
                    wavFilePath = "short.wav",
                    duration = 8_000L,
                    importStatus = TrackImportStatus.READY,
                ),
                TrackEntity(
                    id = "late",
                    projectId = "p",
                    wavFilePath = "late.wav",
                    duration = 10_000L,
                    timelineStartOffsetMs = 12_000L,
                    importStatus = TrackImportStatus.READY,
                ),
            )
        val projection =
            buildIdleProjection(tracks = tracks, selectedTrackIds = setOf("short"))

        assertEquals(8_000L, projection.baseTimelineDurationMs)
        assertTrue(projection.clipsByLaneId["short"]!!.isTimelineBase)
        assertFalse(projection.clipsByLaneId["late"]!!.isTimelineBase)
        assertEquals(8_000L, mixdownTimelineEndMs(tracks, setOf("short")))
        val lateClip = projection.clipsByLaneId["late"]!!
        assertEquals(22_000L, timelineLaneLocalLayoutDurationMs(lateClip))
        assertNotNull(timelineClipLayout(lateClip, timelineLaneLocalLayoutDurationMs(lateClip)))
    }

    @Test
    fun `empty selection yields zero base timeline`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = "p",
                    wavFilePath = "a.wav",
                    duration = 10_000L,
                    importStatus = TrackImportStatus.READY,
                ),
            )

        val projection = buildIdleProjection(tracks = tracks, selectedTrackIds = emptySet())

        assertEquals(0L, projection.baseTimelineDurationMs)
        assertFalse(projection.clipsByLaneId["a"]!!.isTimelineBase)
    }

    @Test
    fun `mixdown timeline end ignores loop region shrink`() {
        val tracks =
            listOf(
                TrackEntity(
                    id = "loop",
                    projectId = "p",
                    wavFilePath = "loop.wav",
                    duration = 20_000L,
                    isLoop = true,
                    loopEndMs = 12_000L,
                    importStatus = TrackImportStatus.READY,
                ),
            )

        assertEquals(20_000L, mixdownTimelineEndMs(tracks, setOf("loop")))
    }

    @Test
    fun `shouldExtendVisibleTimelineForAllLoopedPlayback requires active loop session`() {
        val tracks =
            listOf(
                TrackEntity(id = "a", projectId = "p", wavFilePath = "a.wav", duration = 10_000L, isLoop = true),
                TrackEntity(id = "b", projectId = "p", wavFilePath = "b.wav", duration = 10_000L, isLoop = false),
            )

        assertTrue(
            shouldExtendVisibleTimelineForAllLoopedPlayback(
                playbackSessionActive = true,
                selectedTrackIds = setOf("a"),
                tracks = tracks,
            ),
        )
        assertTrue(
            shouldExtendVisibleTimelineForAllLoopedPlayback(
                playbackSessionActive = true,
                selectedTrackIds = setOf("a", "b"),
                tracks = tracks,
            ),
        )
        assertFalse(
            shouldExtendVisibleTimelineForAllLoopedPlayback(
                playbackSessionActive = true,
                selectedTrackIds = setOf("b"),
                tracks = tracks,
            ),
        )
    }

    private fun buildIdleProjection(
        tracks: List<TrackEntity> = emptyList(),
        playheadPositionMs: Long = 0L,
        selectedTrackIds: Set<String> = tracks.map { it.id }.toSet(),
    ): ProjectTimelineProjection =
        buildProjectTimelineProjection(
            tracks = tracks,
            waveformStatesByTrackId = emptyMap(),
            selectedTrackIds = selectedTrackIds,
            activeRecording = null,
            playheadPositionMs = playheadPositionMs,
            extendVisibleTimelineForAllLoopedPlayback = false,
            extendVisibleTimelineForRecording = false,
        )
}
