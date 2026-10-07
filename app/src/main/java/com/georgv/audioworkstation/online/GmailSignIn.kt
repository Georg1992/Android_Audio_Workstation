package com.georgv.audioworkstation.online

interface GmailSignIn {
    suspend fun idToken(): String
}

class GmailSignInCancelled(cause: Throwable) : Exception("gmail sign-in was cancelled", cause)

class GmailSignInException(message: String) : Exception(message)
