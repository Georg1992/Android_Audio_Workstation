package com.georgv.audioworkstation.online

internal fun profileName(requested: String, email: String): String {
    val typed = requested.trim()
    if (typed.isNotEmpty()) return typed
    return email.substringBefore('@').trim()
}
