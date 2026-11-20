package com.georgv.audioworkstation.ui.theme

import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Raised surfaces are [AppColors.SurfaceRaised]. The page stays [AppColors.Bg].
 * Outline is clear so Material does not draw a black stroke around fields and menus.
 */
fun appLightColorScheme() =
    lightColorScheme(
        primary = AppColors.Line,
        onPrimary = AppColors.Bg,
        background = AppColors.Bg,
        onBackground = AppColors.Line,
        surface = AppColors.SurfaceRaised,
        onSurface = AppColors.Line,
        surfaceVariant = AppColors.SurfacePressed,
        onSurfaceVariant = AppColors.Line,
        outline = Color.Transparent,
        error = AppColors.Red,
        onError = AppColors.Line,
    )

@Composable
fun appTextFieldColors() =
    TextFieldDefaults.colors(
        focusedContainerColor = AppColors.SurfacePressed,
        unfocusedContainerColor = AppColors.SurfacePressed,
        disabledContainerColor = AppColors.SurfacePressed,
        focusedTextColor = AppColors.Line,
        unfocusedTextColor = AppColors.Line,
        disabledTextColor = AppColors.iconMuted,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        cursorColor = AppColors.Line,
        focusedLabelColor = AppColors.Line,
        unfocusedLabelColor = AppColors.iconMuted,
        focusedPlaceholderColor = AppColors.iconMuted,
        unfocusedPlaceholderColor = AppColors.iconMuted,
    )
