package com.aditya.album

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeopleDbTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, PeopleDb::class.java).build()
    private val dao = db.dao()
    private val box = FaceBox(0.1f, 0.1f, 0.3f, 0.3f)

    @After fun close() = db.close()

    private suspend fun person(name: String?, vararg photos: Long) =
        dao.create(Person(name = name, hidden = false, created = 0), photos.map { FaceSpot(it, box) })

    @Test fun notPersonAndUndoPutBackExactlyWhatWasThere() = runBlocking {
        val asha = person("Asha", *LongArray(1200) { it.toLong() }) // more ids than SQLite takes in one statement
        dao.insertNot(listOf(NotPerson(asha, 5000))) // already "Not" before
        val change = dao.notPerson(asha, List(1100) { it.toLong() } + 5000L)
        assertEquals(1100, change.removed.size)
        assertEquals(1100, change.added.size) // 5000 was there already, so Undo must keep it
        assertEquals(100, dao.faces().size)
        dao.undo(change)
        assertEquals(1200, dao.faces().size)
        assertEquals(listOf(NotPerson(asha, 5000)), dao.notPerson())
    }

    @Test fun turningAPhotoTurnsOnlyItsConfirmedBoxes() = runBlocking {
        val asha = person("Asha", 1, 2)
        dao.update(dao.facesOf(1).map { it.copy(box = it.box.rotated(clockwise = true)) })
        assertEquals(box.rotated(true), dao.facesOf(1).single().box)
        assertEquals(box, dao.facesOf(2).single().box)
        assertEquals(setOf(asha), dao.faces().map { it.personId }.toSet())
    }

    @Test fun mergeMovesFacesAndNotsAndDropsTheOther() = runBlocking {
        val asha = person("Asha", 1, 2)
        val twin = person(null, 3)
        dao.insertNot(listOf(NotPerson(asha, 9), NotPerson(twin, 9), NotPerson(twin, 8)))
        dao.merge(twin, asha)
        assertEquals(setOf(1L, 2L, 3L), dao.faces().filter { it.personId == asha }.map { it.mediaId }.toSet())
        assertEquals(setOf(NotPerson(asha, 8), NotPerson(asha, 9)), dao.notPerson().toSet()) // 9 twice became one row
        assertNull(dao.person(twin))
    }

    @Test fun centroidsAreKeptPerPerson() = runBlocking {
        val asha = person("Asha", 1)
        dao.setCentroid(asha, floatArrayOf(0.6f, 0.8f).toBytes(), faces = 12, model = FACE_MODEL)
        val saved = dao.person(asha)!!
        assertEquals(FACE_MODEL, saved.centroidModel)
        assertEquals(12, saved.centroidFaces)
        assertEquals(listOf(0.6f, 0.8f), saved.centroid!!.toFloats().toList())
    }
}
