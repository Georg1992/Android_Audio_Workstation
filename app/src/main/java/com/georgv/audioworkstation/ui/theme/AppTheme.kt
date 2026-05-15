package com.georgv.audioworkstation.ui.theme

import androidx.compose.material3.lightColorScheme

/**
 * Maps neutral hardware-gray tokens onto Material so defaults (menus, dialogs, snackbars)
 * never snap back to generic bright-white surfaces while accents stay palette-driven.
 */
fun appLightColorScheme() =
    lightColorScheme(
        primary = AppColors.Line,
        onPrimary = AppColors.Bg,
        background = AppColors.Bg,
        onBackground = AppColors.Line,
        surface = AppColors.Bg,
        onSurface = AppColors.Line,
        surfaceVariant = AppColors.SurfacePanel,
        onSurfaceVariant = AppColors.Line,
        outline = AppColors.Line,
        error = AppColors.Red,
        onError = AppColors.Line,
    )
