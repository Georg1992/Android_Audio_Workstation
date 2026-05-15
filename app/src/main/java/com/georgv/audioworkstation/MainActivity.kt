package com.georgv.audioworkstation

import android.os.Bundle
import android.view.Window
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.georgv.audioworkstation.ui.AppRoot
import com.georgv.audioworkstation.ui.theme.AppColors
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        window.applyAppChromeBars(AppColors.Bg)

        setContent {
            AppRoot()
        }
    }

    /** System bars track [AppColors.Bg]; light appearance → dark icons (aligned with Line on shell). */
    private fun Window.applyAppChromeBars(bg: Color) {
        val argb = bg.toArgb()
        statusBarColor = argb
        navigationBarColor = argb
        WindowCompat.getInsetsController(this, decorView)?.apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
}
