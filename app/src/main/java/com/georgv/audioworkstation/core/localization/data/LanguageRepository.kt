package com.georgv.audioworkstation.core.localization.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

class LanguageRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dataStore = appContext.languageDataStore

    private object Keys {
        val LANG_TAG = stringPreferencesKey("lang_tag")
    }

    // null = ещё не инициализировано
    val languageTagFlow: Flow<String?> =
        dataStore.data.map { it[Keys.LANG_TAG] }.distinctUntilChanged()

    suspend fun ensureInitialized() {
        val prefs = dataStore.data.first()
        val saved = prefs[Keys.LANG_TAG]
        if (saved == null) {
            val systemTag = Locale.getDefault().toLanguageTag() // e.g. "ru-RU"
            dataStore.edit { it[Keys.LANG_TAG] = systemTag }
            applyLocales(systemTag)
        } else {
            applyLocales(saved)
        }
    }

    suspend fun setLanguageTag(tag: String) {
        dataStore.edit { it[Keys.LANG_TAG] = tag }
        applyLocales(tag)
    }

    private fun applyLocales(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(tag)
        )
    }
}


