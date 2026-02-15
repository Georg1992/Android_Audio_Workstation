package com.georgv.audioworkstation.core.localization.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class LanguageRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dataStore = appContext.languageDataStore

    private object Keys {
        val LANG_TAG = stringPreferencesKey("lang_tag")
    }

    val languageTagFlow: Flow<String> =
        dataStore.data.map { it[Keys.LANG_TAG] ?: "en" }

    suspend fun setLanguageTag(tag: String) {
        dataStore.edit { it[Keys.LANG_TAG] = tag }
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(tag)
        )
    }

}

