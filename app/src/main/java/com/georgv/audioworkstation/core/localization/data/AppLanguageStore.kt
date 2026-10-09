package com.georgv.audioworkstation.core.localization.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject

/** Persists the user-selected locale tag via Datastore (not a Room/domain repository). */
class AppLanguageStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val appContext = context.applicationContext
    private val dataStore = appContext.languageDataStore
    val defaultLanguageTag: String = Locale.getDefault().toLanguageTag()

    val languageTagFlow: Flow<String> =
        dataStore.data
            .map { it[languageTagKey] ?: defaultLanguageTag }
            .distinctUntilChanged()

    suspend fun ensureInitialized() {
        val prefs = dataStore.data.first()
        val saved = prefs[languageTagKey]
        if (saved == null) {
            dataStore.edit { it[languageTagKey] = defaultLanguageTag }
        }
    }

    suspend fun setLanguageTag(tag: String) {
        dataStore.edit { it[languageTagKey] = tag }
    }
}
