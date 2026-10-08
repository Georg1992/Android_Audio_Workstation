package com.georgv.audioworkstation.server

import java.util.Locale

class AccountService(
    private val database: OnlineDatabase,
    private val cognito: CognitoAccessTokens,
) {
    fun currentAccount(token: String): StoredAccount {
        val identity = cognito.verify(token)
        val email = normalizeEmail(identity.email)
        val existing = database.findAccountById(identity.subject)
        if (existing != null) {
            if (existing.email != email) throw OnlineFailure(409, "account email does not match")
            return existing
        }
        database.insertCognitoAccount(identity.subject, email)
        return StoredAccount(
            id = identity.subject,
            email = email,
            passwordSalt = null,
            passwordHash = null,
            googleSubject = null,
        )
    }

    private fun normalizeEmail(emailRaw: String): String {
        val email = emailRaw.trim().lowercase(Locale.US)
        if (!emailPattern.matches(email)) throw OnlineFailure(400, "email is invalid")
        return email
    }

    private companion object {
        val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
