package com.georgv.audioworkstation.online.share

import com.georgv.audioworkstation.ui.playback.FileMixSource
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CardMixSessionTest {
    @Test
    fun secondPlayOfTheSameCardDoesNotLoadTheMixAgain() = runTest {
        val dir = tempDir()
        val projects = ScriptProjects()
        val objects = ScriptObjects()
        val media = HttpCardMedia(projects, DirCache(dir), objects)
        val first = media.mixUrl("song")
        val second = media.mixUrl("song")
        assertEquals(first, second)
        assertEquals(listOf("song"), projects.ids)
        assertEquals(1, objects.downloads)
        assertEquals(byteArrayOf(9, 8, 7).toList(), File(first).readBytes().toList())
    }

    @Test
    fun anUnplayedCardStaysMetadata() = runTest {
        val dir = tempDir()
        val projects = ScriptProjects()
        val objects = ScriptObjects()
        val media = HttpCardMedia(projects, DirCache(dir), objects)
        media.mixUrl("song")
        media.mixUrl("other")
        media.mixUrl("song")
        assertEquals(listOf("song", "other"), projects.ids)
        assertEquals(2, objects.downloads)
    }

    @Test
    fun storedMixReadsTheRequestedRange() {
        val file = File.createTempFile("mix", ".aac")
        file.writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5))
        val bytes = FileMixSource().open(file.absolutePath, 2, 4).use { it.readBytes() }
        assertArrayEquals(byteArrayOf(2, 3, 4), bytes)
    }

    private fun tempDir(): File = File.createTempFile("mix-session", "").apply {
        delete()
        mkdir()
    }

    private class DirCache(private val dir: File) : CardCache {
        override fun imageFile(hash: String): File = File(dir, "image-$hash")

        override fun mixFile(hash: String): File = File(dir, "mix-$hash")
    }

    private class ScriptProjects : SharedProjects {
        val ids = ArrayList<String>()

        override suspend fun list(): List<SharedProject> = error("unused")

        override suspend fun imageUrl(id: String): String = error("unused")

        override suspend fun playback(id: String): List<SharedPlayback> {
            ids += id
            return listOf(SharedPlayback(contentHash = "hash-$id", downloadUrl = "https://example/$id"))
        }

        override suspend fun files(id: String): List<SharedTake> = error("unused")
    }

    private class ScriptObjects : ShareObjectTransport {
        var downloads = 0

        override fun put(url: String, headers: Map<String, String>, file: File) = error("unused")

        override fun download(url: String, destination: File) {
            downloads += 1
            destination.writeBytes(byteArrayOf(9, 8, 7))
        }
    }
}
