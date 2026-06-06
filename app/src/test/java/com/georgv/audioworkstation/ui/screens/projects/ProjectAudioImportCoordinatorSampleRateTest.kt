package com.georgv.audioworkstation.ui.screens.projects

import android.content.ContentResolver
import android.net.Uri
import com.georgv.audioworkstation.core.audio.CompressedAudioMetadata
import com.georgv.audioworkstation.core.audio.UriBackedAudioImportSource
import com.georgv.audioworkstation.core.audio.testProjectAudioImportCoordinator
import com.georgv.audioworkstation.data.repository.ProjectRepository
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProjectAudioImportCoordinatorSampleRateTest {

    @Test
    fun `shouldPromptSampleRateMismatch is true when rates differ`() {
        assertTrue(
            ProjectAudioImportCoordinator.shouldPromptSampleRateMismatch(
                sourceSampleRateHz = 44_100,
                projectSampleRateHz = 48_000,
            ),
        )
    }

    @Test
    fun `shouldPromptSampleRateMismatch is false when rates match`() {
        assertFalse(
            ProjectAudioImportCoordinator.shouldPromptSampleRateMismatch(
                sourceSampleRateHz = 44_100,
                projectSampleRateHz = 44_100,
            ),
        )
    }

    @Test
    fun `createProjectForPendingImport uses source sample rate and strips file extension`() = runTest {
        val dao = FakeProjectDao(projects = emptyList(), tracks = emptyList())
        val repo = ProjectRepository(dao, NoopProjectFileStore)
        val coordinator =
            testProjectAudioImportCoordinator(
                repo,
            )
        val pending =
            PendingCompressedImport(
                source = FakeUriImportSource(Uri.parse("content://test/song.mp3")),
                metadata =
                    CompressedAudioMetadata(
                        durationMs = 5_000L,
                        sampleRate = 44_100,
                        channelCount = 2,
                        mimeType = "audio/mpeg",
                    ),
                suggestedTrackName = "My Song.mp3",
            )

        val outcome = coordinator.createProjectForPendingImport(pending)

        assertTrue(outcome is CreateProjectForImportOutcome.Success)
        val project = (outcome as CreateProjectForImportOutcome.Success).project
        assertEquals(44_100, project.sampleRate)
        assertEquals("My Song", project.name)
    }

    @Test
    fun `createProjectForPendingImport rejects unsupported sample rates`() = runTest {
        val dao = FakeProjectDao(projects = emptyList(), tracks = emptyList())
        val repo = ProjectRepository(dao, NoopProjectFileStore)
        val coordinator = testProjectAudioImportCoordinator(repo)
        val pending =
            PendingCompressedImport(
                source = FakeUriImportSource(Uri.parse("content://test/song.mp3")),
                metadata =
                    CompressedAudioMetadata(
                        durationMs = 5_000L,
                        sampleRate = 96_000,
                        channelCount = 2,
                        mimeType = "audio/mpeg",
                    ),
                suggestedTrackName = "song.mp3",
            )

        assertEquals(
            CreateProjectForImportOutcome.UnsupportedSampleRate,
            coordinator.createProjectForPendingImport(pending),
        )
    }

    private class FakeUriImportSource(
        override val uri: Uri,
    ) : UriBackedAudioImportSource {
        override val contentResolver: ContentResolver
            get() = error("Not used in createProjectForPendingImport test")

        override fun open(): InputStream = error("Not used in createProjectForPendingImport test")
    }
}
