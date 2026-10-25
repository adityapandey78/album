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
        assertEquals(listOf(1L), dao.scoredIn(Category.Food, labelsOf(Category.Food)).map { it.id })
        assertEquals(listOf(Scored(2, 0.9f)), dao.scoredWith("Dog"))
        assertEquals(listOf(1L), dao.idsAt("Goa"))
        assertEquals(listOf(1L), dao.idsAt("India"))
    }

    @Test fun searchesEveryPlaceLevelAndRanksLabels() = runBlocking {
        val baga = Place("Calangute", "Goa", "IN", "India", locality = "Baga", district = "North Goa")
        dao.save(
            listOf(item(1, Category.Pets, baga), item(2, Category.Pets)),
            listOf(ItemLabel(1, "Dog", 0.9f), ItemLabel(2, "Dog", 0.4f), ItemLabel(2, "Cat", 0.8f)),
            emptyList(),
        )
        assertEquals(listOf(1L), dao.idsAt("Baga"))
        assertEquals(listOf(1L), dao.idsAt("North Goa"))
        assertEquals(setOf("Baga", "Calangute", "North Goa", "Goa", "India"), dao.placeNames().first().toSet())
        assertEquals(mapOf(1L to 0.9f, 2L to 0.4f), dao.scoredWith("Dog").associate { it.id to it.score })
        assertEquals(mapOf(1L to 0.9f, 2L to 0.8f), dao.scoredIn(Category.Pets, labelsOf(Category.Pets)).associate { it.id to it.score })
        // The Places page counts the city once, however its photos spread over localities
        assertEquals(listOf(Place("Calangute", "Goa", "IN", "India")), dao.places().first().map { it.place })
    }

    @Test fun estimatedPlacesAreMarkedAndTakenBack() = runBlocking {
        val goa = Place("Calangute", "Goa", "IN", "India", district = "North Goa")
        dao.save(listOf(item(1), item(2)), emptyList(), emptyList())
        dao.setPlaces(mapOf(2L to goa), estimated = true)
        assertEquals(listOf(2L), dao.estimated().first())
        assertEquals(ItemPlace(2, gps = false, estimated = true, place = goa), dao.place(2))
        dao.setPlaces(mapOf(2L to null), estimated = true)
        assertEquals(ItemPlace(2, gps = false, estimated = false, place = null), dao.place(2))
    }

    @Test fun textSplitsAtPunctuationAndKeepsLettersLikeØ() = runBlocking {
        dao.save(listOf(item(1)), emptyList(), listOf(ItemText(1, "E-ticket 15/08/2024 Tromsø")))
        assertEquals(listOf(1L), dao.idsWithText("\"e tick*\"")) // what idsWithTokens sends for "e-tick"
        assertEquals(listOf(1L), dao.idsWithText("\"15 08 2024\"")) // idsWithPhrase for "15/08/2024"
        assertEquals(listOf(1L), dao.idsWithText("\"tromsø*\""))
        assertEquals(emptyList<Long>(), dao.idsWithText("tromso*")) // why search mustn't fold ø for text
    }
}
