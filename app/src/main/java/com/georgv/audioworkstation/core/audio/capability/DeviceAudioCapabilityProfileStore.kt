package com.georgv.audioworkstation.core.audio.capability

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class DeviceAudioCapabilityProfileStore @Inject constructor(
    @ApplicationContext context: Context,
) : DeviceAudioCapabilityProfilePersistence {
    private val dataStore = context.applicationContext.capabilityProfileDataStore

    override suspend fun save(profile: DeviceAudioCapabilityProfile) {
        dataStore.edit { prefs ->
            prefs[prefKey(profile.profileId)] = DeviceAudioCapabilityProfileCodec.encode(profile)
        }
    }

    override suspend fun load(profileId: String): DeviceAudioCapabilityProfile? {
        val encoded = dataStore.data.first()[prefKey(profileId)] ?: return null
        val profile = DeviceAudioCapabilityProfileCodec.decode(encoded) ?: return null
        if (profile.profileId != profileId) {
            return null
        }
        return profile
    }

    override suspend fun clear(profileId: String) {
        dataStore.edit { prefs ->
            prefs.remove(prefKey(profileId))
        }
    }

    override suspend fun listProfileIds(): List<String> =
        dataStore.data.first().asMap().entries.mapNotNull { (preferenceKey, encoded) ->
            if (!preferenceKey.name.startsWith(PROFILE_KEY_PREFIX)) {
                return@mapNotNull null
            }
            DeviceAudioCapabilityProfileCodec.decode(encoded as String)?.profileId
                ?: preferenceKey.name
                    .removePrefix(PROFILE_KEY_PREFIX)
                    .let(DeviceAudioCapabilityProfileId::profileIdFromStorageKey)
        }

    private fun prefKey(profileId: String) =
        stringPreferencesKey("$PROFILE_KEY_PREFIX${DeviceAudioCapabilityProfileId.storageKey(profileId)}")

    private companion object {
        const val PROFILE_KEY_PREFIX = "capability_"
    }
}
