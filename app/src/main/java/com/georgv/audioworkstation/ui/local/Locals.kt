package com.georgv.audioworkstation.ui.local

import androidx.compose.runtime.staticCompositionLocalOf
import com.georgv.audioworkstation.core.localization.LanguageViewModel

val LocalLanguageVm = staticCompositionLocalOf<LanguageViewModel> {
    error("LocalLanguageVm not provided")
}