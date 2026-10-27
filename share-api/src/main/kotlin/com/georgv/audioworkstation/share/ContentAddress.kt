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

/**
 * HeadObject says an object is missing in two ways. 404 is the normal answer.
 * 403 is the answer when the caller can read an object that exists but cannot
 * list the bucket, which is how S3 hides keys that are not there.
 */
internal fun absentObject(status: Int): Boolean =
    status == HiddenMissing || status == NotFound

private const val NotFound = 404
private const val HiddenMissing = 403
