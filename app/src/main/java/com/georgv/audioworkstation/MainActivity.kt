package com.georgv.audioworkstation

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.toArgb
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.georgv.audioworkstation.ui.AppRoot
import com.georgv.audioworkstation.ui.theme.AppColors
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val barArgb = AppColors.Bg.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(barArgb, barArgb),
            navigationBarStyle = SystemBarStyle.light(barArgb, barArgb),
        )

        setContent {
            AppRoot()
        }
    }
}
