package com.georgv.audioworkstation.core.localization.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

val Context.languageDataStore by preferencesDataStore(name = "language_prefs")



