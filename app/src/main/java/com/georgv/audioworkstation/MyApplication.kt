package com.georgv.audioworkstation

import android.app.Application
import android.content.pm.ApplicationInfo
import com.georgv.audioworkstation.ui.components.TimelineGeometryDebug
import com.georgv.audioworkstation.ui.navigation.NavTransitionDiagnostics
import com.georgv.audioworkstation.ui.screens.library.LibraryDiagnostics
import com.georgv.audioworkstation.ui.screens.projects.ProjectDiagnostics
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        TimelineGeometryDebug.loggingEnabled = debuggable
        NavTransitionDiagnostics.loggingEnabled = debuggable
        LibraryDiagnostics.loggingEnabled = debuggable
        ProjectDiagnostics.loggingEnabled = debuggable
    }
}
