package com.aditya.album

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexDbTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, IndexDb::class.java)
        .allowMainThreadQueries() // the blocking search lookups
        .build()
    private val dao = db.dao()

    @After fun close() = db.close()

    private fun item(id: Long, category: Category = Category.Documents, place: Place? = null) =
        IndexedItem(id, modified = 1, size = 1, version = 1, category, lat = null, lng = null, located = true, place)

    @Test fun textIsFoundByWordPrefixIgnoringCaseAndAccents() = runBlocking {
        dao.save(listOf(item(1), item(2)), emptyList(), listOf(ItemText(1, "TAX INVOICE\nCafé Mondegar, Colaba"), ItemText(2, "Boarding pass GOA")))
        assertEquals(listOf(1L), dao.idsWithText("invo*"))
        assertEquals(listOf(1L), dao.idsWithText("cafe*"))
        assertEquals(listOf(2L), dao.idsWithText("goa*"))
        assertEquals(emptyList<Long>(), dao.idsWithText("voice*")) // prefixes of words, not any substring
    }

    @Test fun savingAgainReplacesTextAndRemovingForgetsIt() = runBlocking {
        dao.save(listOf(item(1)), emptyList(), listOf(ItemText(1, "old receipt")))
        dao.save(listOf(item(1)), emptyList(), listOf(ItemText(1, "new invoice")))
        assertEquals(emptyList<Long>(), dao.idsWithText("old*"))
        assertEquals(listOf(1L), dao.idsWithText("invoice*"))
        dao.remove(listOf(1))
        assertEquals(emptyList<Long>(), dao.idsWithText("invoice*"))
    }

    @Test fun lookupsBySearchNames() = runBlocking {
        val goa = Place("Panaji", "Goa", "IN", "India")
        dao.save(listOf(item(1, Category.Food, goa), item(2, Category.Pets)), listOf(ItemLabel(2, "Dog", 0.9f)), emptyList())
        assertEquals(listOf(1L), dao.idsIn(Category.Food))
        assertEquals(listOf(2L), dao.idsWith("Dog"))
        assertEquals(listOf(1L), dao.idsAt("Goa"))
        assertEquals(listOf(1L), dao.idsAt("India"))
    }
}
