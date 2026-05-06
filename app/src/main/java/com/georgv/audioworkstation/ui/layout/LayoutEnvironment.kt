package com.georgv.audioworkstation.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
/**
 * Generic buckets for adaptive layout decisions. Values are viewport-based — not OEM or device
 * model-specific.
 */
enum class WidthClass { Compact, Medium, Expanded }

enum class HeightClass { Compact, Medium, Expanded }

enum class DensityTier {
    Standard,
    /** Very high-density screens (fine layout adjustments only; keep usage minimal). */
    ExtraDense,
}

/**
 * Describes the current UI environment for layout policy code. Prefer passing values from
 * constraints or Configuration — no hidden singletons or "layout engines".
 */
data class LayoutEnvironment(
    val widthClass: WidthClass,
    val heightClass: HeightClass,
    val isLandscape: Boolean,
    val availableWidth: Dp,
    val availableHeight: Dp,
    val density: Float,
    val densityTier: DensityTier,
)

fun layoutEnvironmentFromScreen(
    screenWidthDp: Float,
    screenHeightDp: Float,
    isLandscape: Boolean,
    density: Float,
): LayoutEnvironment {
    val shortest = kotlin.math.min(screenWidthDp, screenHeightDp)
    val longest = kotlin.math.max(screenWidthDp, screenHeightDp)
    val widthForClass =
        if (isLandscape) {
            longest
        } else {
            shortest
        }
    val heightForClass =
        if (isLandscape) {
            shortest
        } else {
            longest
        }
    val widthClass =
        when {
            widthForClass < 600f -> WidthClass.Compact
            widthForClass < 840f -> WidthClass.Medium
            else -> WidthClass.Expanded
        }
    val heightClass =
        when {
            heightForClass < 480f -> HeightClass.Compact
            heightForClass < 720f -> HeightClass.Medium
            else -> HeightClass.Expanded
        }
    val densityTier =
        if (density >= 3f) {
            DensityTier.ExtraDense
        } else {
            DensityTier.Standard
        }
    return LayoutEnvironment(
        widthClass = widthClass,
        heightClass = heightClass,
        isLandscape = isLandscape,
        availableWidth = screenWidthDp.dp,
        availableHeight = screenHeightDp.dp,
        density = density,
        densityTier = densityTier,
    )
}

@Composable
fun rememberLayoutEnvironment(): LayoutEnvironment {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val isLandscape =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    return remember(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        configuration.orientation,
        density,
    ) {
        layoutEnvironmentFromScreen(
            screenWidthDp = configuration.screenWidthDp.toFloat(),
            screenHeightDp = configuration.screenHeightDp.toFloat(),
            isLandscape = isLandscape,
            density = density,
        )
    }
}
