package com.georgv.audioworkstation.online

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class CredentialManagerGmailSignIn(
    private val context: Context,
    private val webClientId: String,
    private val credentials: CredentialManager,
) : GmailSignIn {
    override suspend fun idToken(): String {
        if (webClientId.isBlank()) throw GmailSignInException("gmail sign-in is not configured")
        return readToken(googleRequest())
    }

    private fun googleRequest(): GetCredentialRequest {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .build()
        return GetCredentialRequest.Builder().addCredentialOption(option).build()
    }

    private suspend fun readToken(request: GetCredentialRequest): String {
        try {
            val result = credentials.getCredential(context, request)
            val google = GoogleIdTokenCredential.createFrom(result.credential.data)
            if (google.idToken.isBlank()) throw GmailSignInException("gmail sign-in did not return a token")
            return google.idToken
        } catch (cancelled: GetCredentialCancellationException) {
            throw GmailSignInCancelled(cancelled)
        }
    }
}
