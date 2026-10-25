package com.aditya.album

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionsDbTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, CollectionsDb::class.java).build()
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
}
