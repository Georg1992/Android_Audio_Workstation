package com.georgv.audioworkstation.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContentAddressTest {
    @Test
    fun storageKeyIsTheHash() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(hash, storageKey(hash))
    }

    @Test
    fun existingObjectIsNotUploadedAgain() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val grant = uploadGrant(hash, objectExists = true)
        assertEquals(hash, grant.storageKey)
        assertTrue(grant.alreadyStored)
    }

    @Test
    fun missingObjectNeedsAnUpload() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val grant = uploadGrant(hash, objectExists = false)
        assertFalse(grant.alreadyStored)
    }

    @Test
    fun aHashThatIsNotSha256IsRejected() {
        try {
            storageKey("not-a-hash")
            fail("storage key accepted a value that is not SHA-256")
        } catch (failure: ShareFailure) {
            assertEquals(400, failure.status)
        }
    }
}
