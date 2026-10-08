package com.georgv.audioworkstation.ui.screens.community

internal enum class CommunityDestination {
    Pending,
    SignIn,
    Home,
}

internal fun communityDestination(sessionKnown: Boolean, signedIn: Boolean): CommunityDestination =
    when {
        !sessionKnown -> CommunityDestination.Pending
        signedIn -> CommunityDestination.Home
        else -> CommunityDestination.SignIn
    }
