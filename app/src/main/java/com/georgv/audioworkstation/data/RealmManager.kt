package com.georgv.audioworkstation.data

import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration

object RealmManager {
    
    private val configuration = RealmConfiguration.Builder(
        schema = setOf(Song::class, Track::class)
    )
    .name("audioworkstation.realm")
    .schemaVersion(2) // Increment version to force schema update
    .deleteRealmIfMigrationNeeded() // Delete and recreate if schema changed
    .build()
    
    val realm: Realm by lazy {
        Realm.open(configuration)
    }
    
    fun closeRealm() {
        if (realm.isClosed().not()) {
            realm.close()
        }
    }
}