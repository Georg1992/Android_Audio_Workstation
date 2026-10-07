package com.georgv.audioworkstation.server

import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

class AccountService(
    private val database: OnlineDatabase,
    private val google: GoogleIdTokens,
) {
    private val random = SecureRandom()

    fun createAccount(emailRaw: String, password: String): RegisteredAccount {
        val email = normalizeEmail(emailRaw)
        requirePassword(password)
        val id = UUID.randomUUID().toString()
        val salt = PasswordHasher.salt()
        val hash = PasswordHasher.hash(password, salt)
        database.insertAccount(id, email, salt.toHex(), hash.toHex(), googleSubject = null)
        return RegisteredAccount(accountId = id, email = email)
    }

    fun createSession(emailRaw: String, password: String): AccountSession {
        val email = normalizeEmail(emailRaw)
        requirePassword(password)
        val account = database.findAccount(email) ?: throw OnlineFailure(401, INVALID_PASSWORD)
        val salt = account.passwordSalt
        val hash = account.passwordHash
        if (salt == null || hash == null) throw OnlineFailure(401, INVALID_PASSWORD)
        if (!PasswordHasher.matches(password, hexToBytes(salt), hexToBytes(hash))) {
            throw OnlineFailure(401, INVALID_PASSWORD)
        }
        return openSession(account.id, account.email)
    }

    fun createGoogleSession(idToken: String): AccountSession {
        val identity = google.verify(idToken)
        val email = normalizeEmail(identity.email)
        val linked = database.findAccountByGoogleSubject(identity.subject)
        if (linked != null) return sessionForLinkedGoogle(linked, email)
        return sessionForEmail(email, identity.subject)
    }

    fun deleteSession(token: String) {
        if (!database.deleteSession(token)) throw OnlineFailure(401, "session is not valid")
    }

    fun currentAccount(token: String): StoredAccount =
        database.accountForToken(token) ?: throw OnlineFailure(401, "session is not valid")

    private fun sessionForLinkedGoogle(account: StoredAccount, email: String): AccountSession {
        if (account.email != email) throw OnlineFailure(409, "gmail account does not match the registered email")
        return openSession(account.id, account.email)
    }

    private fun sessionForEmail(email: String, subject: String): AccountSession {
        val existing = database.findAccount(email)
        if (existing == null) return openSession(insertGoogleAccount(email, subject), email)
        val linked = existing.googleSubject
        if (linked != null && linked != subject) {
            throw OnlineFailure(409, "email is linked to a different gmail account")
        }
        if (linked == null) database.setGoogleSubject(existing.id, subject)
        return openSession(existing.id, existing.email)
    }

    private fun insertGoogleAccount(email: String, subject: String): String {
        val id = UUID.randomUUID().toString()
        database.insertAccount(id, email, passwordSalt = null, passwordHash = null, googleSubject = subject)
        return id
    }

    private fun openSession(accountId: String, email: String): AccountSession {
        val tokenBytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(tokenBytes)
        val token = tokenBytes.toHex()
        database.insertSession(token, accountId)
        return AccountSession(token = token, accountId = accountId, email = email)
    }

    private fun normalizeEmail(emailRaw: String): String {
        val email = emailRaw.trim().lowercase(Locale.US)
        if (!emailPattern.matches(email)) throw OnlineFailure(400, "email is invalid")
        return email
    }

    private fun requirePassword(password: String) {
        if (password.isBlank()) throw OnlineFailure(400, "password is required")
    }

    private companion object {
        const val TOKEN_BYTES = 32
        const val INVALID_PASSWORD = "invalid email or password"
        val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
