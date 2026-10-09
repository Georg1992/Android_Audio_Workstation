package com.georgv.audioworkstation.core.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguagesTest {
    @Test
    fun keepsTheShippedLanguageTags() {
        assertEquals("en", supportedLanguageTag("en"))
        assertEquals("en", supportedLanguageTag("en-US"))
        assertEquals("ru", supportedLanguageTag("ru"))
        assertEquals("ru", supportedLanguageTag("ru-RU"))
        assertEquals("zh-CN", supportedLanguageTag("zh-CN"))
        assertEquals("zh-CN", supportedLanguageTag("zh"))
    }
}
