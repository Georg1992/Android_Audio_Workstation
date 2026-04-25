package com.georgv.audioworkstation.data.db.entities

/**
 * Sync state of a row relative to a remote backing store.
 *
 * Stored as a string so Room can persist it directly and so future debugging
 * via `sqlite3` is human-readable. This is plumbing for the upcoming online
 * collaboration layer — every row is currently created with [LOCAL] and there
 * is no consumer of the other states yet.
 */
enum class SyncStatus {
    /** Row exists only on this device. No remote counterpart. */
    LOCAL,

    /** Row is being uploaded / downloaded right now. */
    SYNCING,

    /** Row matches the remote copy. */
    SYNCED,

    /** Local and remote diverged; needs explicit resolution. */
    CONFLICT
}
