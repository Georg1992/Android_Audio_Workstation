package com.georgv.audioworkstation.ui.screens.library

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.ProjectMixdownState
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryProjectCardInteractionTest {

    @Test
    fun `body click ignored while mixing`() {
        val outcome =
            resolveLibraryCardBodyClick(
                mixdown = ProjectMixdownState(isMixing = true, progress = 0.5f),
                isCurrentlyPlaying = false,
                mixFileExists = false,
            )
        assertEquals(LibraryCardBodyClickOutcome.IgnoredWhileMixing, outcome)
    }

    @Test
    fun `body click without mix shows no mix available`() {
        val outcome =
            resolveLibraryCardBodyClick(
                mixdown = ProjectMixdownState(),
                isCurrentlyPlaying = false,
                mixFileExists = false,
            )
        assertEquals(LibraryCardBodyClickOutcome.NoMixAvailable, outcome)
    }

    @Test
    fun `body click with missing file shows no mix available`() {
        val outcome =
            resolveLibraryCardBodyClick(
                mixdown = ProjectMixdownState(mixdownWavPath = "/tmp/missing.wav"),
                isCurrentlyPlaying = false,
                mixFileExists = false,
            )
        assertEquals(LibraryCardBodyClickOutcome.NoMixAvailable, outcome)
    }

    @Test
    fun `body click with existing mix toggles preview`() {
        val outcome =
            resolveLibraryCardBodyClick(
                mixdown = ProjectMixdownState(mixdownWavPath = "/tmp/mixdown.wav"),
                isCurrentlyPlaying = false,
                mixFileExists = true,
            )
        assertEquals(LibraryCardBodyClickOutcome.TogglePreview, outcome)
    }

    @Test
    fun `subtitle reflects mix state`() {
        assertEquals(
            R.string.library_no_mix_yet,
            libraryCardSubtitleResId(
                ProjectMixdownState(),
                isCurrentlyPlaying = false,
                mixFileExists = false,
            ),
        )
        assertEquals(
            R.string.library_preview_mix_hint,
            libraryCardSubtitleResId(
                ProjectMixdownState(mixdownWavPath = "/tmp/mix.wav"),
                isCurrentlyPlaying = false,
                mixFileExists = true,
            ),
        )
        assertEquals(
            R.string.library_preview_playing_hint,
            libraryCardSubtitleResId(
                ProjectMixdownState(mixdownWavPath = "/tmp/mix.wav"),
                isCurrentlyPlaying = true,
                mixFileExists = true,
            ),
        )
    }
}
