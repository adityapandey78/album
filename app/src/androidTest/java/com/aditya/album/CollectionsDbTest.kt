package com.aditya.album

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionsDbTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, CollectionsDb::class.java).build()
    private val dao = db.dao()

    @After fun close() = db.close()

    private suspend fun newCollection(name: String) = dao.insert(MediaCollection(name = name, created = 0, updated = 0))

    private fun items(id: Long, vararg media: Long, added: Long = 1) = media.map { CollectionItem(id, it, added) }

    @Test fun addingAgainKeepsTheFirstAddedTime() = runBlocking {
        val goa = newCollection("Goa trip")
        assertEquals(2, dao.add(goa, items(goa, 1, 2, added = 1), now = 1))
        assertEquals(1, dao.add(goa, items(goa, 2, 3, added = 5), now = 5))
        assertEquals(listOf(1L, 1L, 5L), dao.items().first().sortedBy { it.mediaId }.map { it.added })
    }

    @Test fun removeAndUndoRestoreTheSameRows() = runBlocking {
        val goa = newCollection("Goa trip")
        dao.add(goa, items(goa, *LongArray(2500) { it.toLong() }), now = 1) // more ids than SQLite takes in one statement
        val removed = dao.remove(goa, List(2400) { it.toLong() }, now = 2)
        assertEquals(2400, removed.size)
        assertEquals(100, dao.items().first().size)
        dao.add(goa, removed, now = 3)
        assertEquals(2500, dao.items().first().count { it.added == 1L })
    }

    @Test fun deletingACollectionDeletesOnlyItsItems() = runBlocking {
        val goa = newCollection("Goa trip")
        val family = newCollection("Family")
        dao.add(goa, items(goa, 1, 2), now = 1)
        dao.add(family, items(family, 2), now = 1)
        dao.delete(goa)
        assertEquals(listOf(CollectionItem(family, 2, 1)), dao.items().first())
    }

    @Test fun addingToADeletedCollectionAddsNothing() = runBlocking {
        val goa = newCollection("Goa trip")
        dao.delete(goa)
        assertEquals(0, dao.add(goa, items(goa, 1), now = 1))
    }

    @Test fun forgettingAPhotoRemovesItFromEveryCollection() = runBlocking {
        val goa = newCollection("Goa trip")
        val family = newCollection("Family")
        dao.add(goa, items(goa, 1, 2), now = 1)
        dao.add(family, items(family, 1), now = 1)
        dao.forget(listOf(1))
        assertEquals(listOf(2L), dao.items().first().map { it.mediaId })
    }

    // ---------- Album groups and covers ----------

    private val goa = "pictures/goa 2024/"
    private val manali = "pictures/manali/"

    @Test fun movingAnAlbumOutOfAGroupItEmptiesDeletesTheGroupAndUndoBringsItBackWhole() = runBlocking {
        val trips = dao.newGroup("Trips", goa, now = 1).made!!
        dao.putCover(AlbumCover(groupKey(trips), 42))
        val move = dao.newGroup("Beaches", goa, now = 2) // Goa was Trips' only album
        assertEquals(listOf(trips), move.emptied)
        assertNull(dao.group(trips))
        assertNull(dao.cover(groupKey(trips)))
        dao.moveBack(goa, move.before)
        assertEquals("Trips", dao.group(trips)?.name) // the same id, so its pin still fits
        assertEquals(42L, dao.cover(groupKey(trips))?.mediaId)
        assertEquals(trips, dao.memberOf(goa)?.groupId)
        assertNull(dao.group(move.made!!)) // the group the move made, empty again, is gone
    }

    @Test fun anAlbumIsInOneGroupAtATime() = runBlocking {
        val trips = dao.newGroup("Trips", goa, now = 1).made!!
        dao.moveToGroup(manali, trips, now = 1)
        val family = dao.newGroup("Family", manali, now = 2).made!!
        assertEquals(listOf(goa), dao.membersOf(trips).map { it.albumKey })
        assertEquals(listOf(manali), dao.membersOf(family).map { it.albumKey })
        val out = dao.moveToGroup(goa, null, now = 3)
        assertEquals(listOf(trips), out.emptied)
        assertNull(dao.memberOf(goa))
    }

    @Test fun ungroupKeepsTheAlbumsAndUndoPutsThemBack() = runBlocking {
        val trips = dao.newGroup("Trips", goa, now = 1).made!!
        dao.moveToGroup(manali, trips, now = 1)
        val snapshot = dao.ungroup(trips)!!
        assertNull(dao.group(trips))
        assertEquals(emptyList<GroupMember>(), dao.members().first())
        dao.newGroup("Family", manali, now = 2) // moved elsewhere meanwhile: stays there
        dao.undoUngroup(snapshot)
        assertEquals(listOf(goa), dao.membersOf(trips).map { it.albumKey })
        assertEquals("Family", dao.group(dao.memberOf(manali)!!.groupId)?.name)
    }

    @Test fun deletingACollectionTakesItOutOfItsGroup() = runBlocking {
        val bills = newCollection("Bills")
        val trips = dao.newGroup("Trips", collectionKey(bills), now = 1).made!!
        dao.putCover(AlbumCover(collectionKey(bills), 7))
        dao.delete(bills)
        assertNull(dao.memberOf(collectionKey(bills)))
        assertNull(dao.cover(collectionKey(bills)))
        assertNull(dao.group(trips)) // it was the group's only album
    }

    @Test fun aRenamedFolderKeepsItsGroupAndCover() = runBlocking {
        val trips = dao.newGroup("Trips", goa, now = 1).made!!
        dao.putCover(AlbumCover(goa, 9))
        dao.rekey(goa, "pictures/goa trip/")
        assertEquals(trips, dao.memberOf("pictures/goa trip/")?.groupId)
        assertEquals(9L, dao.cover("pictures/goa trip/")?.mediaId)
        assertNull(dao.memberOf(goa))
    }

    @Test fun fixesComeBackAsTheyWereOnUndo() = runBlocking {
        val before = MediaFix(1, modified = 10, taken = 100)
        dao.putFixes(listOf(before))
        val ids = List(1200) { it.toLong() + 1 } // more ids than SQLite takes in one statement
        assertEquals(listOf(before), dao.fixesBefore(ids))
        dao.putFixes(ids.map { MediaFix(it, modified = 11, taken = 200, placeFixed = true, place = Place("Calangute", "Goa", "IN", "India")) })
        dao.restoreFixes(ids, listOf(before))
        assertEquals(listOf(before), dao.allFixes())
    }

    /**
     * A collections.db as v1.1.0 left it (version 1, built with the SQL Room exported in app/schemas/.../CollectionsDb/1.json),
     * opened by 1.1.1: every collection and item survives, with its added time, and the new tables work on the upgraded file.
     */
    @Test fun version1UpgradesWithEveryCollectionKept() = runBlocking {
        val name = "collections-upgrade-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { v1 ->
            listOf(
                "CREATE TABLE IF NOT EXISTS `collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `created` INTEGER NOT NULL, `updated` INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `collection_items` (`collectionId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, `added` INTEGER NOT NULL, PRIMARY KEY(`collectionId`, `mediaId`), FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '80e294a46cf837ec9bdd51f8359f2f99')",
                "INSERT INTO collections VALUES (1, 'Goa trip', 100, 300)",
                "INSERT INTO collections VALUES (2, 'Bills', 200, 200)",
                "INSERT INTO collection_items VALUES (1, 10, 101)",
                "INSERT INTO collection_items VALUES (1, 11, 102)",
                "INSERT INTO collection_items VALUES (2, 10, 201)",
            ).forEach(v1::execSQL)
            v1.version = 1
        }
        val upgraded = Room.databaseBuilder(context, CollectionsDb::class.java, name).build()
        try {
            val dao = upgraded.dao()
            assertEquals(
                listOf(MediaCollection(1, "Goa trip", 100, 300), MediaCollection(2, "Bills", 200, 200)),
                dao.collections().first(),
            )
            assertEquals(
                setOf(CollectionItem(1, 10, 101), CollectionItem(1, 11, 102), CollectionItem(2, 10, 201)),
                dao.items().first().toSet(),
            )
            assertEquals(emptyList<AlbumGroup>(), dao.groups().first())
            // The new tables work on the upgraded file, foreign keys and collections together
            val trips = dao.newGroup("Trips", collectionKey(1), now = 400).made!!
            dao.putCover(AlbumCover(collectionKey(1), 11))
            dao.putFixes(listOf(MediaFix(10, modified = 5, taken = 1_000)))
            assertEquals(trips, dao.memberOf(collectionKey(1))?.groupId)
            dao.delete(1)
            assertNull(dao.group(trips))
            assertEquals(listOf(CollectionItem(2, 10, 201)), dao.items().first())
            assertEquals(1, dao.allFixes().size)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
