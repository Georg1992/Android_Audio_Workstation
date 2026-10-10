package com.georgv.audioworkstation.share

private val Sha256Hex = Regex("^[0-9a-f]{64}$")

/** Object key for one audio file. The same bytes always use this one key. */
internal fun storageKey(contentHash: String): String {
    if (!Sha256Hex.matches(contentHash)) throw ShareFailure(400, "content hash must be SHA-256")
    return contentHash
}

internal data class UploadGrant(
    val storageKey: String,
    val alreadyStored: Boolean,
)

/** A hash that is already in the bucket is not uploaded again. */
internal fun uploadGrant(contentHash: String, objectExists: Boolean): UploadGrant =
    UploadGrant(storageKey = storageKey(contentHash), alreadyStored = objectExists)
