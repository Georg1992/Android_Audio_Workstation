package com.georgv.audioworkstation.online

interface CognitoSignIn {
    suspend fun signIn(): AccountSession

    suspend fun signOut(refreshToken: String)
}

class CognitoSignInCancelled : Exception("cognito sign-in was cancelled")

open class CognitoSignInException(message: String, cause: Throwable? = null) : Exception(message, cause)
