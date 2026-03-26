package com.georgv.audioworkstation.core.localization.data

import android.content.Context
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

    val languageTagFlow: Flow<String?> =
        dataStore.data.map { it[Keys.LANG_TAG] }.distinctUntilChanged()

    suspend fun ensureInitialized() {
        val prefs = dataStore.data.first()
        val saved = prefs[Keys.LANG_TAG]
        if (saved == null) {
            val systemTag = Locale.getDefault().toLanguageTag()
            dataStore.edit { it[Keys.LANG_TAG] = systemTag }
        }
    }

    suspend fun setLanguageTag(tag: String) {
        dataStore.edit { it[Keys.LANG_TAG] = tag }
    }
}
