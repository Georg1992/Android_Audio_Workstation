package com.georgv.audioworkstation.online

internal fun acceptableEmail(email: String): Boolean = EmailPattern.matches(email)

internal fun acceptablePassword(password: String): Boolean {
    if (password.length < MinPasswordLength) return false
    if (password.none(Char::isUpperCase)) return false
    if (password.none(Char::isLowerCase)) return false
    return password.any(Char::isDigit)
}

private val EmailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

private const val MinPasswordLength = 8
