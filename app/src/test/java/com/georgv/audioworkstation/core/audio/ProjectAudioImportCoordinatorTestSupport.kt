package com.georgv.audioworkstation.core.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.core.session.ProjectAudioImportCoordinator
import java.io.File

internal fun testProjectAudioImportCoordinator(
    repo: ProjectRepository,
    audioFilePathProvider: AudioFilePathProvider = TempDirAudioFilePathProvider(),
    context: Context = ApplicationProvider.getApplicationContext(),
    wavAudioImporter: WavAudioImporter = WavAudioImporter(),
    dispatchers: AppDispatchers = TestAppDispatchers(),
): ProjectAudioImportCoordinator =
    ProjectAudioImportCoordinator(
        repo = repo,
        audioImporter = DelegatingAudioImporter(
            wavAudioImporter = wavAudioImporter,
            mediaCodecAudioImporter = MediaCodecAudioImporter(context),
        ),
        audioFilePathProvider = audioFilePathProvider,
        dispatchers = dispatchers,
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
    com.georgv.audioworkstation.core.audio.waveform.writeMonoPcm16Wav(
        file = file,
        samples = samples,
        channelCount = channelCount,
        sampleRateHz = sampleRateHz,
    )
    return AudioImportSource { file.inputStream() }
}
