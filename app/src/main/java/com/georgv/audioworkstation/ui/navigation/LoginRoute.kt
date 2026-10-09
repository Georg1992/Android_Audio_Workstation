package com.georgv.audioworkstation.ui.navigation

internal object LoginRoute {
    fun opensFrom(route: String?): Boolean {
        val path = route?.substringBefore('?') ?: return false
        if (path == Routes.LOGIN) return false
        return path != Routes.COMMUNITY
    }

    fun allowedDuring(recording: Boolean): Boolean = !recording
}
