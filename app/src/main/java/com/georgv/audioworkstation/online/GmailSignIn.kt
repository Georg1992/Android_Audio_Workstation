package com.georgv.audioworkstation.online

interface GmailSignIn {
    suspend fun idToken(): String
}

class GmailSignInCancelled(cause: Throwable) : Exception("gmail sign-in was cancelled", cause)

open class GmailSignInException(message: String, cause: Throwable? = null) : Exception(message, cause)

class GmailSignInNotConfigured : GmailSignInException("gmail sign-in is not configured")
