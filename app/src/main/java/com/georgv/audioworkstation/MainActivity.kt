package com.georgv.audioworkstation

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.toArgb
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.content.Intent
import com.georgv.audioworkstation.online.CognitoRedirects
import com.georgv.audioworkstation.ui.AppRoot
import com.georgv.audioworkstation.ui.theme.AppColors
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var cognitoRedirects: CognitoRedirects

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        cognitoRedirects.offer(intent?.data)

        val barArgb = AppColors.Bg.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(barArgb, barArgb),
            navigationBarStyle = SystemBarStyle.light(barArgb, barArgb),
        )

        setContent {
            AppRoot()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        cognitoRedirects.offer(intent.data)
    }
}
