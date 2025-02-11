package com.georgv.audioworkstation

import android.app.Application
import io.realm.Realm
import io.realm.RealmConfiguration

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize Realm
        Realm.init(this)

        // Optional: Configure Realm (define default configuration)
        val configuration = RealmConfiguration.Builder()
            .name("myrealm.realm")  // Optional: Set the name of your database
            .schemaVersion(1)       // Optional: Schema version for migrations
            .deleteRealmIfMigrationNeeded() // Optional: Delete database if migration is needed
            .build()

        // Set default Realm configuration
        Realm.setDefaultConfiguration(configuration)
    }
}