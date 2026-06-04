package com.georgv.audioworkstation.core.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.screens.projects.ProjectAudioImportCoordinator
import java.io.File

internal fun testProjectAudioImportCoordinator(
    repo: ProjectRepository,
    audioFilePathProvider: AudioFilePathProvider = TempDirAudioFilePathProvider(),
    context: Context = ApplicationProvider.getApplicationContext(),
    wavAudioImporter: WavAudioImporter = WavAudioImporter(),
): ProjectAudioImportCoordinator =
    ProjectAudioImportCoordinator(
        repo = repo,
        wavAudioImporter = wavAudioImporter,
        mediaCodecAudioImporter = MediaCodecAudioImporter(context),
        audioFilePathProvider = audioFilePathProvider,
        context = context,
    )

internal fun wavImportSource(
    samples: ShortArray,
    sampleRateHz: Int = 44_100,
    channelCount: Int = 1,
): AudioImportSource {
    val file =
        File.createTempFile("import-test", ".wav").apply {
            deleteOnExit()
        }
    com.georgv.audioworkstation.ui.components.writeMonoPcm16Wav(
        file = file,
        samples = samples,
        channelCount = channelCount,
        sampleRateHz = sampleRateHz,
    )
    return AudioImportSource { file.inputStream() }
}
