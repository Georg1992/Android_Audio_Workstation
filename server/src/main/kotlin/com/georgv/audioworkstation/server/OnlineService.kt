package com.georgv.audioworkstation.server

import java.io.InputStream
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

class OnlineService(
    private val database: OnlineDatabase,
    private val storage: ContentAddressedStorage,
) {
    private val random = SecureRandom()

    fun createAccount(emailRaw: String, password: String): AccountSession {
        val email = normalizeEmail(emailRaw)
        requirePassword(password)
        val id = UUID.randomUUID().toString()
        val salt = PasswordHasher.salt()
        val hash = PasswordHasher.hash(password, salt)
        database.insertAccount(id, email, salt.toHex(), hash.toHex())
        return openSession(id, email)
    }

    fun createSession(emailRaw: String, password: String): AccountSession {
        val email = normalizeEmail(emailRaw)
        requirePassword(password)
        val account = database.findAccount(email) ?: throw OnlineFailure(401, "invalid email or password")
        val salt = hexToBytes(account.passwordSalt)
        val expected = hexToBytes(account.passwordHash)
        if (!PasswordHasher.matches(password, salt, expected)) {
            throw OnlineFailure(401, "invalid email or password")
        }
        return openSession(account.id, account.email)
    }

    fun deleteSession(token: String) {
        if (!database.deleteSession(token)) {
            throw OnlineFailure(401, "session is not valid")
        }
    }

    fun currentAccount(token: String): StoredAccount = account(token)

    fun createProject(token: String, title: String): SharedProjectRecord {
        val owner = account(token)
        val normalized = title.trim()
        if (normalized.isEmpty()) throw OnlineFailure(400, "title is required")
        val id = UUID.randomUUID().toString()
        val record = SharedProjectRecord(id = id, ownerAccountId = owner.id, title = normalized)
        database.insertProject(record.id, record.ownerAccountId, record.title)
        return record
    }

    fun project(token: String, projectId: String): SharedProjectDetails {
        val owner = account(token)
        val record = ownedProject(owner.id, projectId)
        return SharedProjectDetails(
            id = record.id,
            ownerAccountId = record.ownerAccountId,
            title = record.title,
            files = database.filesForProject(record.id),
        )
    }

    fun storageKey(token: String, projectId: String, clientTrackId: String, contentHash: String, size: Long): String {
        val owner = account(token)
        ownedProject(owner.id, projectId)
        requireTrack(clientTrackId)
        requireContentHash(contentHash)
        if (size < 0L) throw OnlineFailure(400, "size must be zero or greater")
        return contentHash
    }

    fun write(token: String, storageKey: String, body: InputStream) {
        account(token)
        storage.put(storageKey, body)
    }

    fun commitFile(token: String, projectId: String, file: SharedFileRecord) {
        val owner = account(token)
        ownedProject(owner.id, projectId)
        requireTrack(file.clientTrackId)
        requireContentHash(file.contentHash)
        if (file.storageKey != file.contentHash) {
            throw OnlineFailure(400, "storage key must be the content hash")
        }
        val stored = storage.file(file.storageKey)
        if (!stored.isFile) throw OnlineFailure(400, "file is not stored")
        if (stored.length() != file.size) throw OnlineFailure(400, "size does not match the stored file")
        if (Sha256Hex.ofFile(stored) != file.contentHash) {
            throw OnlineFailure(400, "content hash does not match the stored file")
        }
        database.insertFile(projectId, file)
    }

    private fun openSession(accountId: String, email: String): AccountSession {
        val tokenBytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(tokenBytes)
        val token = tokenBytes.toHex()
        database.insertSession(token, accountId)
        return AccountSession(token = token, accountId = accountId, email = email)
    }

    private fun account(token: String): StoredAccount =
        database.accountForToken(token) ?: throw OnlineFailure(401, "session is not valid")

    private fun ownedProject(accountId: String, projectId: String): SharedProjectRecord {
        val record = database.findProject(projectId) ?: throw OnlineFailure(404, "shared project was not found")
        if (record.ownerAccountId != accountId) throw OnlineFailure(403, "shared project belongs to another account")
        return record
    }

    private fun normalizeEmail(emailRaw: String): String {
        val email = emailRaw.trim().lowercase(Locale.US)
        if (!emailPattern.matches(email)) throw OnlineFailure(400, "email is invalid")
        return email
    }

    private fun requirePassword(password: String) {
        if (password.isBlank()) throw OnlineFailure(400, "password is required")
    }

    private fun requireTrack(clientTrackId: String) {
        if (clientTrackId.isBlank()) throw OnlineFailure(400, "client track id is required")
    }

    private companion object {
        const val TOKEN_BYTES = 32
        val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
