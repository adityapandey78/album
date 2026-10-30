package com.aditya.album

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeopleDbTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, PeopleDb::class.java).build()
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

    @Test fun turningAPhotoTurnsOnlyItsConfirmedBoxesAndCover() = runBlocking {
        val asha = person("Asha", 1, 2)
        dao.setCover(asha, 1, box.left, box.top, box.right, box.bottom)
        dao.rotate(1, clockwise = true)
        assertEquals(box.rotated(true), dao.facesOf(1).single().box)
        assertEquals(box, dao.facesOf(2).single().box)
        assertEquals(box.rotated(true), dao.person(asha)!!.cover)
        assertEquals(setOf(asha), dao.faces().map { it.personId }.toSet())
    }

    @Test fun mergeMovesFacesNotsAndDifferentPairsAndDropsTheOther() = runBlocking {
        val asha = person("Asha", 1, 2)
        val twin = person(null, 3)
        val ravi = person("Ravi", 4)
        dao.insertNot(listOf(NotPerson(asha, 9), NotPerson(twin, 9), NotPerson(twin, 8)))
        dao.insertDifferent(DifferentPeople(minOf(twin, ravi), maxOf(twin, ravi)))
        dao.insertDifferent(DifferentPeople(minOf(asha, twin), maxOf(asha, twin))) // merged anyway: the latest word wins
        dao.merge(twin, asha)
        assertEquals(setOf(1L, 2L, 3L), dao.faces().filter { it.personId == asha }.map { it.mediaId }.toSet())
        assertEquals(setOf(NotPerson(asha, 8), NotPerson(asha, 9)), dao.notPerson().toSet()) // 9 twice became one row
        assertEquals(listOf(DifferentPeople(minOf(asha, ravi), maxOf(asha, ravi))), dao.different())
        assertNull(dao.person(twin))
    }

    @Test fun coverIsPinnedAndUnpinned() = runBlocking {
        val asha = person("Asha", 1)
        dao.setCover(asha, 7, 0.2f, 0.2f, 0.4f, 0.5f)
        val pinned = dao.names().first().single()
        assertEquals(7L, pinned.coverMediaId)
        assertEquals(FaceBox(0.2f, 0.2f, 0.4f, 0.5f), pinned.cover)
        dao.setCover(asha, null, null, null, null, null)
        assertNull(dao.names().first().single().cover)
    }

    @Test fun lockingConfirmsTheirFacesAndHidesTheirPhotos() = runBlocking {
        val asha = person("Asha", 1, 2)
        val ravi = person("Ravi", 3)
        dao.lock(asha, listOf(PersonFace(personId = asha, mediaId = 4, box = box))) // a face only the index had grouped
        assertEquals(listOf(asha), dao.lockedIds().first())
        assertEquals(setOf(1L, 2L, 4L), dao.lockedPhotos().first().toSet())
        assertEquals(true, dao.names().first().single { it.id == asha }.locked)
        assertEquals(false, dao.names().first().single { it.id == ravi }.locked)
        dao.setLocked(asha, false)
        assertEquals(emptyList<Long>(), dao.lockedIds().first())
        assertEquals(emptyList<Long>(), dao.lockedPhotos().first())
        assertEquals(3, dao.facesOfPerson(asha).size) // confirmed faces stay theirs after Unhide
    }

    @Test fun petsKeepTheirSpeciesThroughMerges() = runBlocking {
        val bruno = dao.create(Person(name = "Bruno", hidden = false, created = 0, species = Species.Dog), listOf(FaceSpot(1, WHOLE), FaceSpot(2, WHOLE)))
        val pup = dao.create(Person(name = null, hidden = false, created = 0, species = Species.Dog), listOf(FaceSpot(3, WHOLE)))
        val asha = person("Asha", 4)
        dao.merge(pup, bruno)
        val names = dao.names().first().associateBy { it.id }
        assertEquals(Species.Dog, names.getValue(bruno).species)
        assertNull(names.getValue(asha).species)
        assertEquals(setOf(1L, 2L, 3L), dao.facesOfPerson(bruno).map { it.mediaId }.toSet())
        dao.rotate(1, clockwise = true)
        assertEquals(WHOLE, dao.facesOf(1).single().box) // a pet's box is the whole photo, whichever way up
    }

    /**
     * A people.db as v1.1 left it (version 3, built with the SQL Room exported in app/schemas/.../PeopleDb/3.json), opened
     * by v1.1.1: every name, face, cover, lock, "Not" row and "Different" pair survives, and nobody becomes a pet.
     */
    @Test fun version3UpgradesWithEveryoneKeptAndNoPets() = runBlocking {
        val name = "people-upgrade-3-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { v3 ->
            listOf(
                "CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `hidden` INTEGER NOT NULL, `created` INTEGER NOT NULL, `centroid` BLOB, `centroidFaces` INTEGER NOT NULL, `centroidModel` INTEGER NOT NULL, `coverMediaId` INTEGER, `locked` INTEGER NOT NULL DEFAULT 0, `cover_left` REAL, `cover_top` REAL, `cover_right` REAL, `cover_bottom` REAL)",
                "CREATE TABLE IF NOT EXISTS `person_faces` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, `left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, FOREIGN KEY(`personId`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_person_faces_personId` ON `person_faces` (`personId`)",
                "CREATE INDEX IF NOT EXISTS `index_person_faces_mediaId` ON `person_faces` (`mediaId`)",
                "CREATE TABLE IF NOT EXISTS `not_person` (`personId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, PRIMARY KEY(`personId`, `mediaId`), FOREIGN KEY(`personId`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_not_person_mediaId` ON `not_person` (`mediaId`)",
                "CREATE TABLE IF NOT EXISTS `different_people` (`a` INTEGER NOT NULL, `b` INTEGER NOT NULL, PRIMARY KEY(`a`, `b`), FOREIGN KEY(`a`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`b`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_different_people_b` ON `different_people` (`b`)",
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '24c9e15954aeca3d60b114d6b83ec4aa')",
                "INSERT INTO people VALUES (1, 'Asha', 0, 100, NULL, 0, 0, 10, 1, 0.1, 0.1, 0.3, 0.3)",
                "INSERT INTO people VALUES (2, NULL, 1, 200, NULL, 0, 0, NULL, 0, NULL, NULL, NULL, NULL)",
                "INSERT INTO person_faces VALUES (1, 1, 10, 0.1, 0.1, 0.3, 0.3)",
                "INSERT INTO person_faces VALUES (2, 2, 11, 0.5, 0.5, 0.7, 0.7)",
                "INSERT INTO not_person VALUES (1, 12)",
                "INSERT INTO different_people VALUES (1, 2)",
            ).forEach(v3::execSQL)
            v3.version = 3
        }
        val upgraded = Room.databaseBuilder(context, PeopleDb::class.java, name).build()
        try {
            val dao = upgraded.dao()
            val people = dao.people().associateBy { it.id }
            assertEquals("Asha", people.getValue(1).name)
            assertEquals(true, people.getValue(2).hidden)
            assertEquals(10L, people.getValue(1).coverMediaId)
            assertEquals(FaceBox(0.1f, 0.1f, 0.3f, 0.3f), people.getValue(1).cover)
            assertEquals(listOf(true, false), listOf(people.getValue(1).locked, people.getValue(2).locked))
            assertEquals(listOf(null, null), people.values.map { it.species })
            assertEquals(2, dao.faces().size)
            assertEquals(listOf(NotPerson(1, 12)), dao.notPerson())
            assertEquals(listOf(DifferentPeople(1, 2)), dao.different())
            assertEquals(listOf(10L), dao.lockedPhotos().first())
            // The new column works on the upgraded file
            val bruno = dao.create(Person(name = "Bruno", hidden = false, created = 300, species = Species.Dog), listOf(FaceSpot(13, WHOLE)))
            assertEquals(Species.Dog, dao.person(bruno)!!.species)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * A people.db as v1.0 left it (version 2, built with the SQL Room exported in app/schemas/.../PeopleDb/2.json), opened
     * by v1.1: every name, face, cover, "Not" row and "Different" pair survives, and nobody starts locked.
     */
    @Test fun version2UpgradesWithEveryoneKeptAndNobodyLocked() = runBlocking {
        val name = "people-upgrade-2-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { v2 ->
            listOf(
                "CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `hidden` INTEGER NOT NULL, `created` INTEGER NOT NULL, `centroid` BLOB, `centroidFaces` INTEGER NOT NULL, `centroidModel` INTEGER NOT NULL, `coverMediaId` INTEGER, `cover_left` REAL, `cover_top` REAL, `cover_right` REAL, `cover_bottom` REAL)",
                "CREATE TABLE IF NOT EXISTS `person_faces` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, `left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, FOREIGN KEY(`personId`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_person_faces_personId` ON `person_faces` (`personId`)",
                "CREATE INDEX IF NOT EXISTS `index_person_faces_mediaId` ON `person_faces` (`mediaId`)",
                "CREATE TABLE IF NOT EXISTS `not_person` (`personId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, PRIMARY KEY(`personId`, `mediaId`), FOREIGN KEY(`personId`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_not_person_mediaId` ON `not_person` (`mediaId`)",
                "CREATE TABLE IF NOT EXISTS `different_people` (`a` INTEGER NOT NULL, `b` INTEGER NOT NULL, PRIMARY KEY(`a`, `b`), FOREIGN KEY(`a`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`b`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_different_people_b` ON `different_people` (`b`)",
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '2a55312fc5dce0bfa4cbbf0d1273826d')",
                "INSERT INTO people VALUES (1, 'Asha', 0, 100, NULL, 0, 0, 10, 0.1, 0.1, 0.3, 0.3)",
                "INSERT INTO people VALUES (2, NULL, 1, 200, NULL, 0, 0, NULL, NULL, NULL, NULL, NULL)",
                "INSERT INTO person_faces VALUES (1, 1, 10, 0.1, 0.1, 0.3, 0.3)",
                "INSERT INTO person_faces VALUES (2, 2, 11, 0.5, 0.5, 0.7, 0.7)",
                "INSERT INTO not_person VALUES (1, 12)",
                "INSERT INTO different_people VALUES (1, 2)",
            ).forEach(v2::execSQL)
            v2.version = 2
        }
        val upgraded = Room.databaseBuilder(context, PeopleDb::class.java, name).build()
        try {
            val dao = upgraded.dao()
            val people = dao.people().associateBy { it.id }
            assertEquals("Asha", people.getValue(1).name)
            assertEquals(true, people.getValue(2).hidden)
            assertEquals(10L, people.getValue(1).coverMediaId)
            assertEquals(FaceBox(0.1f, 0.1f, 0.3f, 0.3f), people.getValue(1).cover)
            assertEquals(listOf(false, false), people.values.map { it.locked })
            assertEquals(2, dao.faces().size)
            assertEquals(listOf(NotPerson(1, 12)), dao.notPerson())
            assertEquals(listOf(DifferentPeople(1, 2)), dao.different())
            assertEquals(emptyList<Long>(), dao.lockedIds().first())
            // The new column works on the upgraded file
            dao.lock(2, emptyList())
            assertEquals(listOf(2L), dao.lockedIds().first())
            assertEquals(listOf(11L), dao.lockedPhotos().first())
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * A people.db as v0.9 left it (version 1, built with the SQL Room exported in app/schemas/.../PeopleDb/1.json),
     * opened by this version: every name, face, "Not" row and old column survives, and covers start automatic.
     */
    @Test fun version1UpgradesWithEveryoneKept() = runBlocking {
        val name = "people-upgrade-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { v1 ->
            listOf(
                "CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `hidden` INTEGER NOT NULL, `created` INTEGER NOT NULL, `centroid` BLOB, `centroidFaces` INTEGER NOT NULL, `centroidModel` INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `person_faces` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, `left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, FOREIGN KEY(`personId`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_person_faces_personId` ON `person_faces` (`personId`)",
                "CREATE INDEX IF NOT EXISTS `index_person_faces_mediaId` ON `person_faces` (`mediaId`)",
                "CREATE TABLE IF NOT EXISTS `not_person` (`personId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, PRIMARY KEY(`personId`, `mediaId`), FOREIGN KEY(`personId`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_not_person_mediaId` ON `not_person` (`mediaId`)",
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '91b4598993e84048fcc780a0e5b090db')",
                "INSERT INTO people VALUES (1, 'Asha', 0, 100, X'0000803F', 12, 1)",
                "INSERT INTO people VALUES (2, NULL, 1, 200, NULL, 0, 0)",
                "INSERT INTO person_faces VALUES (1, 1, 10, 0.1, 0.1, 0.3, 0.3)",
                "INSERT INTO person_faces VALUES (2, 2, 11, 0.5, 0.5, 0.7, 0.7)",
                "INSERT INTO not_person VALUES (1, 12)",
            ).forEach(v1::execSQL)
            v1.version = 1
        }
        val upgraded = Room.databaseBuilder(context, PeopleDb::class.java, name).build()
        try {
            val dao = upgraded.dao()
            val people = dao.people().associateBy { it.id }
            assertEquals("Asha", people.getValue(1).name)
            assertEquals(true, people.getValue(2).hidden)
            assertArrayEquals(floatArrayOf(1f), people.getValue(1).centroid!!.toFloats(), 0f)
            assertNull(people.getValue(1).coverMediaId)
            assertNull(people.getValue(1).cover)
            assertEquals(listOf(PersonFace(1, 1, 10, FaceBox(0.1f, 0.1f, 0.3f, 0.3f)), PersonFace(2, 2, 11, FaceBox(0.5f, 0.5f, 0.7f, 0.7f))), dao.faces().sortedBy { it.id })
            assertEquals(listOf(NotPerson(1, 12)), dao.notPerson())
            // The new parts work on the upgraded file, foreign keys included
            dao.insertDifferent(DifferentPeople(1, 2))
            dao.setCover(1, 10, 0.1f, 0.1f, 0.3f, 0.3f)
            assertEquals(10L, dao.person(1)!!.coverMediaId)
            dao.delete(2)
            assertEquals(emptyList<DifferentPeople>(), dao.different())
            assertEquals(1, dao.faces().size)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
