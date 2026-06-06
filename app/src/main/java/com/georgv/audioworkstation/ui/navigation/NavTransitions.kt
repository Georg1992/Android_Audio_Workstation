package com.georgv.audioworkstation.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry

/** Full-screen horizontal slide duration. Enter and exit share this spec for synchronized motion. */
const val NavTransitionDurationMs = 420

/** Debug label for [NavTransitionDiagnostics] — matches [LinearOutSlowInEasing]. */
const val NavTransitionEasingName = "LinearOutSlowIn"

private val navSlideSpec = tween<IntOffset>(
    durationMillis = NavTransitionDurationMs,
    easing = LinearOutSlowInEasing,
)

fun AnimatedContentTransitionScope<NavBackStackEntry>.navForwardEnterTransition(): EnterTransition {
    NavTransitionDiagnostics.logTransitionEnterStart(
        kind = "forwardEnter",
        fromRoute = initialState.destination.route,
        toRoute = targetState.destination.route,
    )
    return slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = navSlideSpec,
    )
}

fun AnimatedContentTransitionScope<NavBackStackEntry>.navForwardExitTransition(): ExitTransition {
    NavTransitionDiagnostics.logTransitionExitStart(
        kind = "forwardExit",
        fromRoute = initialState.destination.route,
        toRoute = targetState.destination.route,
    )
    return slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth },
        animationSpec = navSlideSpec,
    )
}

fun AnimatedContentTransitionScope<NavBackStackEntry>.navBackEnterTransition(): EnterTransition {
    NavTransitionDiagnostics.logTransitionEnterStart(
        kind = "backEnter",
        fromRoute = initialState.destination.route,
        toRoute = targetState.destination.route,
    )
    return slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth },
        animationSpec = navSlideSpec,
    )
}

fun AnimatedContentTransitionScope<NavBackStackEntry>.navBackExitTransition(): ExitTransition {
    NavTransitionDiagnostics.logTransitionExitStart(
        kind = "backExit",
        fromRoute = initialState.destination.route,
        toRoute = targetState.destination.route,
    )
    return slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = navSlideSpec,
    )
}
