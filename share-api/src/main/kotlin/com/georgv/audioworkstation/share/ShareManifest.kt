package com.georgv.audioworkstation.share

import java.sql.Connection
import java.sql.DriverManager

internal class ShareManifest(config: ShareApiConfig) {
    private val url = config.databaseUrl
    private val user = config.databaseUser
    private val password = config.databasePassword

    init {
        connection().use { db ->
            db.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS shared_project (
                      id TEXT PRIMARY KEY,
                      owner_account TEXT NOT NULL,
                      title TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS shared_file (
                      shared_project_id TEXT NOT NULL REFERENCES shared_project(id),
                      client_track_id TEXT NOT NULL,
                      content_hash TEXT NOT NULL,
                      storage_key TEXT NOT NULL,
                      size_bytes BIGINT NOT NULL,
                      PRIMARY KEY (shared_project_id, client_track_id)
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    fun saveProject(id: String, ownerAccount: String, title: String) {
        connection().use { db ->
            val owner = db.prepareStatement("SELECT owner_account FROM shared_project WHERE id = ?").use { query ->
                query.setString(1, id)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            if (owner == null) {
                db.prepareStatement(
                    "INSERT INTO shared_project (id, owner_account, title) VALUES (?, ?, ?)",
                ).use { insert ->
                    insert.setString(1, id)
                    insert.setString(2, ownerAccount)
                    insert.setString(3, title)
                    insert.executeUpdate()
                }
                return
            }
            if (owner != ownerAccount) throw ShareFailure(403, "forbidden")
            db.prepareStatement("UPDATE shared_project SET title = ? WHERE id = ?").use { update ->
                update.setString(1, title)
                update.setString(2, id)
                update.executeUpdate()
            }
        }
    }

    fun requireOwner(projectId: String, ownerAccount: String) {
        connection().use { db ->
            val owner = db.prepareStatement("SELECT owner_account FROM shared_project WHERE id = ?").use { query ->
                query.setString(1, projectId)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            if (owner == null) throw ShareFailure(404, "not found")
            if (owner != ownerAccount) throw ShareFailure(403, "forbidden")
        }
    }

    fun saveFile(
        projectId: String,
        trackId: String,
        contentHash: String,
        storageKey: String,
        size: Long,
    ) {
        connection().use { db ->
            db.prepareStatement(
                """
                INSERT INTO shared_file
                  (shared_project_id, client_track_id, content_hash, storage_key, size_bytes)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (shared_project_id, client_track_id) DO UPDATE SET
                  content_hash = EXCLUDED.content_hash,
                  storage_key = EXCLUDED.storage_key,
                  size_bytes = EXCLUDED.size_bytes
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, projectId)
                statement.setString(2, trackId)
                statement.setString(3, contentHash)
                statement.setString(4, storageKey)
                statement.setLong(5, size)
                statement.executeUpdate()
            }
        }
    }

    fun hasFile(projectId: String, ownerAccount: String, contentHash: String): Boolean =
        connection().use { db ->
            db.prepareStatement(
                """
                SELECT 1
                FROM shared_file
                JOIN shared_project ON shared_project.id = shared_file.shared_project_id
                WHERE shared_file.shared_project_id = ?
                  AND shared_project.owner_account = ?
                  AND shared_file.content_hash = ?
                """.trimIndent(),
            ).use { query ->
                query.setString(1, projectId)
                query.setString(2, ownerAccount)
                query.setString(3, contentHash)
                query.executeQuery().use { rows -> rows.next() }
            }
        }

    private fun connection(): Connection = DriverManager.getConnection(url, user, password)
}
