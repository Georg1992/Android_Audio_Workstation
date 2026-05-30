package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.audio.MasterPeakIndicatorLevel
import com.georgv.audioworkstation.core.audio.MasterPeakMeter
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AudioControlModelsTest {

    @Test
    fun `toRecordingSpec keeps only recording fields the engine currently uses`() {
        val project = ProjectEntity(id = "project-1", sampleRate = 48_000, fileBitDepth = 24)
        val track = TrackEntity(
            id = "track-1",
            projectId = "project-1",
            channelMode = ChannelMode.STEREO
        )

        val spec = project.toRecordingSpec(track)

        assertEquals("project-1", spec.projectId)
        assertEquals("track-1", spec.trackId)
        assertEquals(48_000, spec.sampleRate)
        assertEquals(24, spec.fileBitDepth)
        assertEquals(ChannelMode.STEREO, spec.channelMode)
        assertEquals(0L, spec.timelineStartOffsetMs)
    }

    @Test
    fun `toRecordingSpec copies track timeline start offset for native transport seed`() {
        val project = ProjectEntity(id = "project-1", sampleRate = 48_000, fileBitDepth = 24)
        val track =
            TrackEntity(
                id = "track-1",
                projectId = "project-1",
                channelMode = ChannelMode.MONO,
                timelineStartOffsetMs = 30_000L,
            )

        val spec = project.toRecordingSpec(track)

        assertEquals(30_000L, spec.timelineStartOffsetMs)
    }

    @Test
    fun `toRecordingRequest adds output path for native recording`() {
        val request =
            RecordingSpec(
                projectId = "project-1",
                trackId = "track-1",
                sampleRate = 48_000,
                fileBitDepth = 24,
                channelMode = ChannelMode.STEREO,
                timelineStartOffsetMs = 12_500L,
            ).toRecordingRequest("/tmp/track-1.wav")

        assertEquals(48_000, request.sampleRate)
        assertEquals(24, request.fileBitDepth)
        assertEquals(ChannelMode.STEREO, request.channelMode)
        assertEquals("/tmp/track-1.wav", request.outputPath)
        assertEquals(12_500L, request.timelineStartOffsetMs)
    }

    @Test
    fun `toMultiPlaybackSpec maps recorded tracks into normalized lanes`() {
        val project = ProjectEntity(id = "project-1", sampleRate = 44_100)
        val tracks = listOf(
            TrackEntity(id = "a", projectId = "project-1", wavFilePath = "/tmp/a.wav", gain = 25f),
            TrackEntity(id = "b", projectId = "project-1", wavFilePath = "/tmp/b.wav", gain = 150f),
            TrackEntity(id = "c", projectId = "project-1", wavFilePath = "", gain = 50f)
        )

        val spec = project.toMultiPlaybackSpec(tracks)

        assertEquals(44_100, spec?.sampleRate)
        assertEquals(2, spec?.lanes?.size)
        assertEquals(
            TrackPlaybackLane(
                "a",
                "/tmp/a.wav",
                0.25f,
                timelineClipStartMs = 0L,
                timelineClipDurationMs = 0L,
                loopEnabled = false,
                loopSourceStartMs = 0L,
                loopSourceEndMs = 0L,
            ),
            spec?.lanes?.get(0),
        )
        assertEquals(
            TrackPlaybackLane(
                "b",
                "/tmp/b.wav",
                1f,
                timelineClipStartMs = 0L,
                timelineClipDurationMs = 0L,
                loopEnabled = false,
                loopSourceStartMs = 0L,
                loopSourceEndMs = 0L,
            ),
            spec?.lanes?.get(1),
        )
    }

    @Test
    fun `toMultiPlaybackSpec returns null when no playable lanes remain`() {
        val project = ProjectEntity(id = "project-1")
        val tracks = listOf(TrackEntity(id = "a", projectId = "project-1", wavFilePath = ""))

        assertNull(project.toMultiPlaybackSpec(tracks))
    }

    @Test
    fun `toMultiPlaybackSpec returns null above eight playable lanes`() {
        val project = ProjectEntity(id = "project-1")
        val tracks = (1..9).map { index ->
            TrackEntity(
                id = "track-$index",
                projectId = "project-1",
                wavFilePath = "/tmp/track-$index.wav"
            )
        }

        assertNull(project.toMultiPlaybackSpec(tracks))
    }

    @Test
    fun `MultiPlaybackSpec validates supported sample rate`() {
        expectIllegalArgument {
            MultiPlaybackSpec(
                sampleRate = 96_000,
                lanes = listOf(TrackPlaybackLane("a", "/tmp/a.wav", 1f))
            )
        }
    }

    @Test
    fun `TrackPlaybackLane validates path and normalized gain`() {
        expectIllegalArgument { TrackPlaybackLane("a", "", 1f) }
        expectIllegalArgument { TrackPlaybackLane("a", "/tmp/a.wav", 1.1f) }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}

class MasterPeakMeterTest {

    @Test
    fun `fromPeakHoldLinear formats dBFS with sign and one decimal while playing`() {
        val below = MasterPeakMeter.fromPeakHoldLinear(0.316f, isStopped = false)
        val above = MasterPeakMeter.fromPeakHoldLinear(1.584893f, isStopped = false)

        assertEquals("-10.0 dB", below.peakDbText)
        assertEquals("+4.0 dB", above.peakDbText)
    }

    @Test
    fun `indicatorLevelForPeak uses three-state thresholds`() {
        assertEquals(
            MasterPeakIndicatorLevel.Green,
            MasterPeakMeter.indicatorLevelForPeak(0.98f, isStopped = false),
        )
        assertEquals(
            MasterPeakIndicatorLevel.Yellow,
            MasterPeakMeter.indicatorLevelForPeak(MasterPeakMeter.SOFT_CLIP_THRESHOLD_LINEAR, isStopped = false),
        )
        assertEquals(
            MasterPeakIndicatorLevel.Yellow,
            MasterPeakMeter.indicatorLevelForPeak(1.5f, isStopped = false),
        )
        assertEquals(
            MasterPeakIndicatorLevel.Red,
            MasterPeakMeter.indicatorLevelForPeak(MasterPeakMeter.SEVERE_OVERLOAD_THRESHOLD_LINEAR, isStopped = false),
        )
    }

    @Test
    fun `indicatorLevelForPeak is green for silence while playing`() {
        assertEquals(
            MasterPeakIndicatorLevel.Green,
            MasterPeakMeter.indicatorLevelForPeak(0f, isStopped = false),
        )
    }

    @Test
    fun `fromPeakHoldLinear shows inactive state when stopped`() {
        val meter = MasterPeakMeter.fromPeakHoldLinear(0.5f, isStopped = true)
        assertEquals("0 dB", meter.peakDbText)
        assertEquals(MasterPeakIndicatorLevel.Inactive, meter.indicatorLevel)
    }
}
