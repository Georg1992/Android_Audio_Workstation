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
