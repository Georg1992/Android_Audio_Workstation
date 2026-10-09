package com.georgv.audioworkstation.core.localization

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.georgv.audioworkstation.core.localization.data.languageDataStore
import com.georgv.audioworkstation.core.localization.data.languageTagKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.Locale

/** Locales the app ships. Tags outside this set use English, matching the language chip. */
internal fun supportedLanguageTag(tag: String): String = when {
    tag.startsWith("ru") -> "ru"
    tag.startsWith("zh") -> "zh-CN"
    else -> "en"
}

/** Notification and service strings follow the saved language without recreating the activity. */
internal fun Context.withAppLanguage(): Context {
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(storedLanguageTag()))
    return createConfigurationContext(config)
}

private fun Context.storedLanguageTag(): String = runBlocking {
    val saved = languageDataStore.data.map { it[languageTagKey] }.first()
    saved ?: Locale.getDefault().toLanguageTag()
}
