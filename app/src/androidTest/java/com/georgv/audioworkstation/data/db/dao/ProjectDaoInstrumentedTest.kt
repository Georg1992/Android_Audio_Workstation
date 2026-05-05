package com.georgv.audioworkstation.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.data.db.AppDatabase
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectDaoInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ProjectDao

    @Before
    fun createDb() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.projectDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun deleteTrackAndUpdatePositions_renumbersMiddleRemoval(): Unit = runBlocking {
        val projectId = "p1"
        dao.upsertProject(ProjectEntity(id = projectId, name = "P"))
        val a = track("a", projectId, position = 0)
        val b = track("b", projectId, position = 1)
        val c = track("c", projectId, position = 2)
        dao.upsertTracks(listOf(a, b, c))

        val remaining = listOf(
            a,
            c.copy(position = 1)
        )
        dao.deleteTrackAndUpdatePositions("b", remaining)

        val tracks = dao.observeTracks(projectId).first()
        assertEquals(listOf("a", "c"), tracks.map { it.id })
        assertEquals(listOf(0, 1), tracks.map { it.position })
    }

    @Test
    fun deleteProject_removesChildTracks_viaCascade(): Unit = runBlocking {
        val projectId = "p-cascade"
        dao.upsertProject(ProjectEntity(id = projectId, name = "Cascade"))
        dao.upsertTracks(
            listOf(
                track("t1", projectId, position = 0),
                track("t2", projectId, position = 1)
            )
        )

        dao.deleteProject(projectId)

        assertTrue(dao.observeTracks(projectId).first().isEmpty())
    }

    @Test
    fun updateTracks_persistsReorderedPositions(): Unit = runBlocking {
        val projectId = "p-reorder"
        dao.upsertProject(ProjectEntity(id = projectId, name = "R"))
        val t0 = track("x", projectId, position = 0)
        val t1 = track("y", projectId, position = 1)
        val t2 = track("z", projectId, position = 2)
        dao.upsertTracks(listOf(t0, t1, t2))

        dao.updateTracks(
            listOf(
                t1.copy(position = 0),
                t2.copy(position = 1),
                t0.copy(position = 2)
            )
        )

        val tracks = dao.observeTracks(projectId).first()
        assertEquals(listOf("y", "z", "x"), tracks.map { it.id })
        assertEquals(listOf(0, 1, 2), tracks.map { it.position })
    }

    private fun track(id: String, projectId: String, position: Int) = TrackEntity(
        id = id,
        projectId = projectId,
        name = id,
        channelMode = ChannelMode.MONO,
        position = position
    )
}
