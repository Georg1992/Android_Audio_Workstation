package com.georgv.audioworkstation

import AppRoot
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity


import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : AppCompatActivity() {

    lateinit var permissionRequester: com.georgv.audioworkstation.core.permissions.PermissionRequester
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        permissionRequester = com.georgv.audioworkstation.core.permissions.PermissionRequester(this)

        setContent {
            AppRoot()
        }
    }
}



