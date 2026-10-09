package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.online.acceptableEmail
import com.georgv.audioworkstation.online.acceptablePassword
import java.util.Locale

internal fun normalizedEmail(email: String): String = email.trim().lowercase(Locale.US)

internal fun registerProblem(email: String, password: String, confirm: String): Int? {
    if (!acceptableEmail(email)) return R.string.error_invalid_email
    if (password != confirm) return R.string.error_password_mismatch
    if (!acceptablePassword(password)) return R.string.error_password_invalid
    return null
}

internal fun signInProblem(email: String, password: String): Int? {
    if (!acceptableEmail(email)) return R.string.error_invalid_email
    if (password.isEmpty()) return R.string.error_sign_in_failed
    return null
}

internal fun confirmProblem(email: String, code: String): Int? {
    if (!acceptableEmail(email)) return R.string.error_invalid_email
    if (code.isEmpty()) return R.string.error_confirmation_code
    return null
}

internal fun cognitoMessage(type: String): Int =
    when (type) {
        "InvalidPasswordException" -> R.string.error_password_invalid
        "UsernameExistsException" -> R.string.error_account_exists
        "CodeMismatchException", "ExpiredCodeException" -> R.string.error_confirmation_code
        else -> R.string.error_sign_in_failed
    }
