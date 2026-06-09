package com.georgv.audioworkstation.core.audio.capability

import android.util.Base64

object DeviceAudioCapabilityProfileId {
    fun compute(
        routeKey: String,
        outputActualAudioApi: String,
        inputActualAudioApi: String,
    ): String =
        listOf(
            routeKey,
            "out=$outputActualAudioApi",
            "in=$inputActualAudioApi",
        ).joinToString("|")

    fun storageKey(profileId: String): String =
        Base64.encodeToString(
            profileId.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )

    fun profileIdFromStorageKey(storageKey: String): String? =
        runCatching {
            String(
                Base64.decode(storageKey, Base64.NO_WRAP or Base64.URL_SAFE),
                Charsets.UTF_8,
            )
        }.getOrNull()
}
