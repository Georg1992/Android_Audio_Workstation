package com.georgv.audioworkstation

import android.app.Application
import android.content.pm.ApplicationInfo
import com.georgv.audioworkstation.ui.components.TimelineGeometryDebug
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TimelineGeometryDebug.loggingEnabled =
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
