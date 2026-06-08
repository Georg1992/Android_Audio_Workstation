package com.georgv.audioworkstation.core.audio

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixdownOutputValidatorTest {

    private val validator = MixdownOutputValidator()

    @Test
    fun `rejects non wav file`() {
        val file = File.createTempFile("mixdown-invalid", ".txt").apply { writeText("wav") }
        assertFalse(
            validator.isValidMixdownFile(
                outputPath = file.absolutePath,
                projectSampleRateHz = 44_100,
            ),
        )
    }

    @Test
    fun `accepts valid mono wav at project sample rate`() {
        val file = File.createTempFile("mixdown-valid", ".wav")
        writeConstantPcm16Wav(
            file = file,
            sampleValue = 1_000,
            frameCount = 4_410,
            sampleRateHz = 44_100,
        )
        assertTrue(
            validator.isValidMixdownFile(
                outputPath = file.absolutePath,
                projectSampleRateHz = 44_100,
            ),
        )
    }

    @Test
    fun `rejects wav when duration does not match expected session length`() {
        val file = File.createTempFile("mixdown-short", ".wav")
        writeConstantPcm16Wav(
            file = file,
            sampleValue = 1_000,
            frameCount = 100,
            sampleRateHz = 44_100,
        )
        assertFalse(
            validator.isValidMixdownFile(
                outputPath = file.absolutePath,
                projectSampleRateHz = 44_100,
                expectedDurationMs = 2_000L,
            ),
        )
    }

    @Test
    fun `accepts wav when duration matches expected session length`() {
        val file = File.createTempFile("mixdown-match", ".wav")
        writeConstantPcm16Wav(
            file = file,
            sampleValue = 1_000,
            frameCount = 88_200,
            sampleRateHz = 44_100,
        )
        assertTrue(
            validator.isValidMixdownFile(
                outputPath = file.absolutePath,
                projectSampleRateHz = 44_100,
                expectedDurationMs = 2_000L,
            ),
        )
    }
}
