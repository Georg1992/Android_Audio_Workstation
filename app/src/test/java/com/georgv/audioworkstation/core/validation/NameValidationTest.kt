package com.georgv.audioworkstation.core.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameValidationTest {

    @Test
    fun `blank name returns Invalid Blank`() {
        val result = validateName("   ")

        assertTrue(result is NameValidationResult.Invalid)
        assertEquals(
            NameValidationError.Blank,
            (result as NameValidationResult.Invalid).error
        )
    }

    @Test
    fun `whitespace is normalized into single spaces`() {
        val result = validateName("  My   Project   Name  ")

        assertEquals(
            "My Project Name",
            (result as NameValidationResult.Valid).normalized
        )
    }

    @Test
    fun `name longer than max length returns Invalid TooLong with limit`() {
        val result = validateName("a".repeat(41))

        assertTrue(result is NameValidationResult.Invalid)
        assertEquals(
            NameValidationError.TooLong(DefaultNameMaxLength),
            (result as NameValidationResult.Invalid).error
        )
    }

    @Test
    fun `custom max length is honored`() {
        val result = validateName("abcdef", maxLength = 5)

        assertEquals(
            NameValidationError.TooLong(5),
            (result as NameValidationResult.Invalid).error
        )
    }
}
