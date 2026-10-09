package com.georgv.audioworkstation.core.localization

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Locales the app ships. Tags outside this set use English, matching the language chip. */
internal fun supportedLanguageTag(tag: String): String = when {
    tag.startsWith("ru") -> "ru"
    tag.startsWith("zh") -> "zh-CN"
    else -> "en"
}

/**
 * Applies [tag] to the process so menus, dialogs, and notifications resolve the same
 * resources as the rest of the UI. Compose popups read the activity context, not [ProvideAppLocale].
 */
internal fun applyAppLanguage(tag: String) {
    val desired = supportedLanguageTag(tag)
    val applied = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val appliedIsDifferent = applied.isEmpty() || supportedLanguageTag(applied) != desired
    if (appliedIsDifferent) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(desired))
    }
}

internal fun Context.withAppLanguage(): Context {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return this
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
    return createConfigurationContext(config)
}
