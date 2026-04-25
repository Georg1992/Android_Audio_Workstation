package com.georgv.audioworkstation.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.georgv.audioworkstation.core.localization.LanguageViewModel
import com.georgv.audioworkstation.core.localization.ProvideAppLocale
import com.georgv.audioworkstation.ui.navigation.AppNavHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val languageVm: LanguageViewModel = hiltViewModel()

    val tag by languageVm.currentTag.collectAsStateWithLifecycle()

    MaterialTheme {
        // Material's default ripple animation didn't fit the flat / palette aesthetic, so we
        // disable it globally. Providing `null` to LocalRippleConfiguration removes the indication
        // entirely without having to override every interactive component's `indication` modifier.
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            ProvideAppLocale(languageTag = tag) {
                val navController = rememberNavController()

                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AppNavHost(
                        currentLanguageTag = tag,
                        onSetLanguage = languageVm::setLanguage,
                        navController = navController
                    )
                }
            }
        }
    }
}
