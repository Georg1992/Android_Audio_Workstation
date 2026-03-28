package com.georgv.audioworkstation.core.localization.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject

class LanguageRepository @Inject constructor(
    @ApplicationContext context: Context
) {

    private val appContext = context.applicationContext
    private val dataStore = appContext.languageDataStore
    val defaultLanguageTag: String = Locale.getDefault().toLanguageTag()

    private object Keys {
        val LANG_TAG = stringPreferencesKey("lang_tag")
    }

    val languageTagFlow: Flow<String> =
        dataStore.data
            .map { it[Keys.LANG_TAG] ?: defaultLanguageTag }
            .distinctUntilChanged()

    suspend fun ensureInitialized() {
        val prefs = dataStore.data.first()
        val saved = prefs[Keys.LANG_TAG]
        if (saved == null) {
            dataStore.edit { it[Keys.LANG_TAG] = defaultLanguageTag }
        }
    }

    suspend fun setLanguageTag(tag: String) {
        dataStore.edit { it[Keys.LANG_TAG] = tag }
    }
}
