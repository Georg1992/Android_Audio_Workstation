package com.georgv.audioworkstation.core.audio.capability

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

val Context.capabilityProfileDataStore by preferencesDataStore(name = "audio_capability_profile_prefs")
