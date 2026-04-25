package com.georgv.audioworkstation.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS projects_new (
                id TEXT NOT NULL,
                name TEXT,
                createdAt INTEGER NOT NULL,
                lastOpened INTEGER NOT NULL,
                sampleRate INTEGER NOT NULL,
                fileBitDepth INTEGER NOT NULL,
                tempo REAL NOT NULL,
                timeSignatureNumerator INTEGER NOT NULL,
                timeSignatureDenominator INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT INTO projects_new (
                id,
                name,
                createdAt,
                lastOpened,
                sampleRate,
                fileBitDepth,
                tempo,
                timeSignatureNumerator,
                timeSignatureDenominator
            )
            SELECT
                id,
                name,
                createdAt,
                lastOpened,
                sampleRate,
                16,
                CAST(bpm AS REAL),
                4,
                4
            FROM projects
            """.trimIndent()
        )

        db.execSQL("DROP TABLE projects")
        db.execSQL("ALTER TABLE projects_new RENAME TO projects")

        db.execSQL(
            """
            ALTER TABLE tracks
            ADD COLUMN channelMode TEXT NOT NULL DEFAULT 'MONO'
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE tracks
            ADD COLUMN inputChannel INTEGER NOT NULL DEFAULT 0
            """.trimIndent()
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE tracks
            ADD COLUMN isLoop INTEGER NOT NULL DEFAULT 0
            """.trimIndent()
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE tracks
            ADD COLUMN isImported INTEGER NOT NULL DEFAULT 0
            """.trimIndent()
        )
    }
}

/**
 * Drops columns the app no longer reads:
 *   projects: lastOpened, tempo, timeSignatureNumerator, timeSignatureDenominator
 *   tracks:   inputChannel
 *
 * SQLite on Android < API 31 cannot DROP COLUMN, so we recreate each table.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS projects_new (
                id TEXT NOT NULL,
                name TEXT,
                createdAt INTEGER NOT NULL,
                sampleRate INTEGER NOT NULL,
                fileBitDepth INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO projects_new (id, name, createdAt, sampleRate, fileBitDepth)
            SELECT id, name, createdAt, sampleRate, fileBitDepth FROM projects
            """.trimIndent()
        )
        db.execSQL("DROP TABLE projects")
        db.execSQL("ALTER TABLE projects_new RENAME TO projects")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tracks_new (
                id TEXT NOT NULL,
                projectId TEXT NOT NULL,
                name TEXT,
                channelMode TEXT NOT NULL,
                gain REAL NOT NULL,
                wavFilePath TEXT NOT NULL,
                timeStampStart INTEGER NOT NULL,
                timeStampStop INTEGER,
                duration INTEGER,
                isRecording INTEGER NOT NULL,
                isLoop INTEGER NOT NULL,
                isImported INTEGER NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY(id),
                FOREIGN KEY(projectId) REFERENCES projects(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO tracks_new (
                id, projectId, name, channelMode, gain, wavFilePath,
                timeStampStart, timeStampStop, duration, isRecording, isLoop, isImported, position
            )
            SELECT
                id, projectId, name, channelMode, gain, wavFilePath,
                timeStampStart, timeStampStop, duration, isRecording, isLoop, isImported, position
            FROM tracks
            """.trimIndent()
        )
        db.execSQL("DROP TABLE tracks")
        db.execSQL("ALTER TABLE tracks_new RENAME TO tracks")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_projectId ON tracks(projectId)")
    }
}

/**
 * Adds collaboration plumbing to [projects] and [tracks]. New rows continue
 * to default to a "LOCAL" sync state; the columns are unused by current code
 * and exist only so future online-collaboration work doesn't need another
 * disruptive migration. SQLite ADD COLUMN is the cheap path here — no table
 * rebuild required.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE projects ADD COLUMN remoteUrl TEXT")
        db.execSQL("ALTER TABLE projects ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'LOCAL'")
        db.execSQL("ALTER TABLE projects ADD COLUMN ownerUserId TEXT")
        db.execSQL("ALTER TABLE projects ADD COLUMN editLamport INTEGER NOT NULL DEFAULT 0")

        db.execSQL("ALTER TABLE tracks ADD COLUMN remoteUrl TEXT")
        db.execSQL("ALTER TABLE tracks ADD COLUMN contentHash TEXT")
        db.execSQL("ALTER TABLE tracks ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'LOCAL'")
        db.execSQL("ALTER TABLE tracks ADD COLUMN ownerUserId TEXT")
        db.execSQL("ALTER TABLE tracks ADD COLUMN editLamport INTEGER NOT NULL DEFAULT 0")
    }
}
