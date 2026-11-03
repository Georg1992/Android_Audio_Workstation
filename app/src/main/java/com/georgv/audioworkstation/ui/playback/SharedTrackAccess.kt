package com.georgv.audioworkstation.ui.playback

import android.app.Application
import com.georgv.audioworkstation.online.share.SharedProjects
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

internal object SharedTrackAccess {
    @Volatile
    var application: Application? = null

    fun projects(): SharedProjects {
        val app = application ?: error("shared tracks are not available")
        return EntryPointAccessors.fromApplication(app, SharedTrackEntryPoint::class.java).sharedProjects()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SharedTrackEntryPoint {
    fun sharedProjects(): SharedProjects
}
