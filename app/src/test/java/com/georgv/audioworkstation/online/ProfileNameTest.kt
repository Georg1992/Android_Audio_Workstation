package com.georgv.audioworkstation.online

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileNameTest {
    @Test
    fun keepsATypedProfileName() {
        assertEquals("Ada Lovelace", profileName("  Ada Lovelace  ", "ada@example.com"))
    }

    @Test
    fun usesTheEmailPrefixWhenTheNameIsEmpty() {
        assertEquals("ada", profileName("   ", "ada@example.com"))
    }
}
