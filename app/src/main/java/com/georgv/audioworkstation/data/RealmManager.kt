package com.georgv.audioworkstation.data

import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration

object RealmManager {
    
    private val configuration = RealmConfiguration.Builder(
        schema = setOf(Song::class, Track::class)
    )
    .name("audioworkstation_v3.realm") // New database name to force fresh start
    .schemaVersion(3) // Increment version again
    .deleteRealmIfMigrationNeeded() // Delete and recreate if schema changed
    .build()
    
    val realm: Realm by lazy {
        try {
            val realmInstance = Realm.open(configuration)
            android.util.Log.i("RealmManager", "Realm opened successfully with schema:")
            realmInstance.schema().classes.forEach { clazz ->
                android.util.Log.i("RealmManager", "Schema contains class: ${clazz.name}")
            }
            realmInstance
        } catch (e: Exception) {
            android.util.Log.e("RealmManager", "Failed to open Realm", e)
            throw e
        }
    }
    
    fun closeRealm() {
        if (realm.isClosed().not()) {
            realm.close()
        }
    }
}