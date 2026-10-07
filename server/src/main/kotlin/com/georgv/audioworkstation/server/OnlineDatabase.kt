package com.georgv.audioworkstation.server

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

class OnlineDatabase(file: File) : AutoCloseable {
    private val connection: Connection

    init {
        Class.forName("org.sqlite.JDBC")
        val path = file.absolutePath.replace('\\', '/')
        connection = DriverManager.getConnection("jdbc:sqlite:$path")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS accounts (
                    id TEXT PRIMARY KEY,
                    email TEXT NOT NULL UNIQUE,
                    password_salt TEXT NOT NULL,
                    password_hash TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    token TEXT PRIMARY KEY,
                    account_id TEXT NOT NULL REFERENCES accounts(id)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS shared_projects (
                    id TEXT PRIMARY KEY,
                    owner_account_id TEXT NOT NULL REFERENCES accounts(id),
                    title TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS shared_files (
                    shared_project_id TEXT NOT NULL REFERENCES shared_projects(id),
                    client_track_id TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    storage_key TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    PRIMARY KEY (shared_project_id, client_track_id)
                )
                """.trimIndent(),
            )
        }
    }

    fun insertAccount(id: String, email: String, passwordSalt: String, passwordHash: String) {
        locked {
            connection.prepareStatement(
                "INSERT INTO accounts (id, email, password_salt, password_hash) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, email)
                statement.setString(3, passwordSalt)
                statement.setString(4, passwordHash)
                statement.executeUpdate()
            }
        }
    }

    fun findAccount(email: String): StoredAccount? = locked {
        connection.prepareStatement(
            "SELECT id, email, password_salt, password_hash FROM accounts WHERE email = ?",
        ).use { statement ->
            statement.setString(1, email)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    StoredAccount(
                        id = rows.getString("id"),
                        email = rows.getString("email"),
                        passwordSalt = rows.getString("password_salt"),
                        passwordHash = rows.getString("password_hash"),
                    )
                }
            }
        }
    }

    fun insertSession(token: String, accountId: String) {
        locked {
            connection.prepareStatement(
                "INSERT INTO sessions (token, account_id) VALUES (?, ?)",
            ).use { statement ->
                statement.setString(1, token)
                statement.setString(2, accountId)
                statement.executeUpdate()
            }
        }
    }

    fun deleteSession(token: String): Boolean = locked {
        connection.prepareStatement("DELETE FROM sessions WHERE token = ?").use { statement ->
            statement.setString(1, token)
            statement.executeUpdate() > 0
        }
    }

    fun accountForToken(token: String): StoredAccount? = locked {
        connection.prepareStatement(
            """
            SELECT accounts.id, accounts.email, accounts.password_salt, accounts.password_hash
            FROM sessions
            JOIN accounts ON accounts.id = sessions.account_id
            WHERE sessions.token = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, token)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    StoredAccount(
                        id = rows.getString("id"),
                        email = rows.getString("email"),
                        passwordSalt = rows.getString("password_salt"),
                        passwordHash = rows.getString("password_hash"),
                    )
                }
            }
        }
    }

    fun insertProject(id: String, ownerAccountId: String, title: String) {
        locked {
            connection.prepareStatement(
                "INSERT INTO shared_projects (id, owner_account_id, title) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, ownerAccountId)
                statement.setString(3, title)
                statement.executeUpdate()
            }
        }
    }

    fun findProject(id: String): SharedProjectRecord? = locked {
        connection.prepareStatement(
            "SELECT id, owner_account_id, title FROM shared_projects WHERE id = ?",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    SharedProjectRecord(
                        id = rows.getString("id"),
                        ownerAccountId = rows.getString("owner_account_id"),
                        title = rows.getString("title"),
                    )
                }
            }
        }
    }

    fun insertFile(projectId: String, file: SharedFileRecord) {
        locked {
            connection.prepareStatement(
                """
                INSERT INTO shared_files (
                    shared_project_id, client_track_id, content_hash, storage_key, size
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, projectId)
                statement.setString(2, file.clientTrackId)
                statement.setString(3, file.contentHash)
                statement.setString(4, file.storageKey)
                statement.setLong(5, file.size)
                statement.executeUpdate()
            }
        }
    }

    fun filesForProject(projectId: String): List<SharedFileRecord> = locked {
        connection.prepareStatement(
            """
            SELECT client_track_id, content_hash, storage_key, size
            FROM shared_files
            WHERE shared_project_id = ?
            ORDER BY client_track_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, projectId)
            statement.executeQuery().use { rows ->
                val files = mutableListOf<SharedFileRecord>()
                while (rows.next()) {
                    files.add(
                        SharedFileRecord(
                            clientTrackId = rows.getString("client_track_id"),
                            contentHash = rows.getString("content_hash"),
                            storageKey = rows.getString("storage_key"),
                            size = rows.getLong("size"),
                        ),
                    )
                }
                files
            }
        }
    }

    override fun close() {
        connection.close()
    }

    private fun <T> locked(block: () -> T): T =
        synchronized(connection) {
            try {
                block()
            } catch (error: SQLException) {
                if (error.message?.contains("UNIQUE") == true) {
                    throw OnlineFailure(409, "record already exists")
                }
                throw error
            }
        }
}

data class StoredAccount(
    val id: String,
    val email: String,
    val passwordSalt: String,
    val passwordHash: String,
)
