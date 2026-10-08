package com.georgv.audioworkstation.server

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
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
                    password_salt TEXT,
                    password_hash TEXT,
                    google_subject TEXT UNIQUE,
                    cognito_sub TEXT UNIQUE,
                    CHECK (
                        (password_salt IS NULL) = (password_hash IS NULL)
                        AND (
                            password_hash IS NOT NULL
                            OR google_subject IS NOT NULL
                            OR cognito_sub IS NOT NULL
                        )
                    )
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

    fun insertCognitoAccount(id: String, email: String) {
        locked {
            connection.prepareStatement(
                """
                INSERT INTO accounts (id, email, password_salt, password_hash, google_subject, cognito_sub)
                VALUES (?, ?, NULL, NULL, NULL, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, email)
                statement.setString(3, id)
                statement.executeUpdate()
            }
        }
    }

    fun findAccountById(id: String): StoredAccount? = locked {
        connection.prepareStatement(
            "SELECT $ACCOUNT_COLUMNS FROM accounts WHERE id = ?",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) readAccount(rows) else null }
        }
    }

    fun findAccount(email: String): StoredAccount? = locked {
        connection.prepareStatement(
            "SELECT $ACCOUNT_COLUMNS FROM accounts WHERE email = ?",
        ).use { statement ->
            statement.setString(1, email)
            statement.executeQuery().use { rows -> if (rows.next()) readAccount(rows) else null }
        }
    }

    fun findAccountByGoogleSubject(googleSubject: String): StoredAccount? = locked {
        connection.prepareStatement(
            "SELECT $ACCOUNT_COLUMNS FROM accounts WHERE google_subject = ?",
        ).use { statement ->
            statement.setString(1, googleSubject)
            statement.executeQuery().use { rows -> if (rows.next()) readAccount(rows) else null }
        }
    }

    fun setGoogleSubject(accountId: String, googleSubject: String) {
        locked {
            connection.prepareStatement(
                "UPDATE accounts SET google_subject = ? WHERE id = ? AND google_subject IS NULL",
            ).use { statement ->
                statement.setString(1, googleSubject)
                statement.setString(2, accountId)
                if (statement.executeUpdate() != 1) {
                    throw OnlineFailure(409, "email is linked to a different gmail account")
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
            SELECT $ACCOUNT_COLUMNS
            FROM sessions
            JOIN accounts ON accounts.id = sessions.account_id
            WHERE sessions.token = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, token)
            statement.executeQuery().use { rows -> if (rows.next()) readAccount(rows) else null }
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

    private fun readAccount(rows: ResultSet): StoredAccount =
        StoredAccount(
            id = rows.getString("id"),
            email = rows.getString("email"),
            passwordSalt = rows.getString("password_salt"),
            passwordHash = rows.getString("password_hash"),
            googleSubject = rows.getString("google_subject"),
        )

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

    private companion object {
        const val ACCOUNT_COLUMNS =
            "accounts.id AS id, accounts.email AS email, accounts.password_salt AS password_salt, " +
                "accounts.password_hash AS password_hash, accounts.google_subject AS google_subject"
    }
}

data class StoredAccount(
    val id: String,
    val email: String,
    val passwordSalt: String?,
    val passwordHash: String?,
    val googleSubject: String?,
)
