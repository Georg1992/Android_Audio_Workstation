package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStorageGuardTest {

  private val reserveBytes = RecordingStorageGuard.DEFAULT_RESERVE_BYTES
  private val query =
      object : RecordingStorageFsQuery {
          var bytes: Long? = reserveBytes + 1L

          override fun availableBytes(path: String): Long? = bytes
      }
  private val guard = RecordingStorageGuard(query)

  @Test
  fun `canStart is false when available is below reserve`() {
      query.bytes = reserveBytes

      assertFalse(guard.canStartRecording("/tmp/project"))
  }

  @Test
  fun `canStart is false when storage query fails`() {
      query.bytes = null

      assertFalse(guard.canStartRecording("/tmp/project"))
  }

  @Test
  fun `canStart is true when available exceeds reserve`() {
      query.bytes = reserveBytes + 1L

      assertTrue(guard.canStartRecording("/tmp/project"))
  }

  @Test
  fun `usable bytes subtracts reserve`() {
      assertEquals(100L, guard.usableBytesAfterReserve(reserveBytes + 100L))
      assertEquals(0L, guard.usableBytesAfterReserve(reserveBytes))
  }

  @Test
  fun `estimated remaining ms uses pcm rate`() {
      val available = reserveBytes + 88_200L
      val remainingMs =
          guard.estimatedRemainingRecordingMs(
              availableBytes = available,
              sampleRate = 44_100,
              channelCount = 1,
              bitDepth = 16,
          )

      assertEquals(1_000L, remainingMs)
  }

  @Test
  fun `pcm bytes per second uses bit depth and channels`() {
      assertEquals(176_400L, guard.pcmBytesPerSecond(44_100, 2, 16))
  }
}
