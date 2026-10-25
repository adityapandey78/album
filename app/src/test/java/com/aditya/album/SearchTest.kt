package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class SearchTest {
    private val today = LocalDate.of(2026, 10, 25) // a Sunday
    private val dates = DateSource(today, DayOfWeek.MONDAY)

    private fun item(id: Long, date: LocalDate = today, video: Boolean = false, favorite: Boolean = false, screenshot: Boolean = false, name: String = "img_$id.jpg") =
        SearchItem(id, date.toEpochDay(), video, favorite, screenshot, name)

    // A small library: what the smart index knows, by id
    private val categoryIds = mapOf(Category.Pets to setOf(1L, 2L), Category.Food to setOf(3L), Category.Sunsets to setOf(4L))
    private val labelIds = mapOf("Dog" to setOf(1L), "Cat" to setOf(2L), "Food" to setOf(3L, 5L), "Sky" to setOf(4L, 6L), "Hot dog" to setOf(7L))
    private val placeIds = mapOf("Panaji" to setOf(1L, 3L), "Goa" to setOf(1L, 3L, 4L), "New Delhi" to setOf(5L), "Delhi" to setOf(6L), "Bengaluru" to setOf(7L))
    private val textIds = mapOf("invoice" to setOf(8L), "goa" to setOf(9L))
    private val sources = listOf(
        thingSource(categoryIds.keys, labelIds.keys, { categoryIds[it].orEmpty() }, { labelIds[it].orEmpty() }),
        placeSource(
            listOf(Place("Panaji", "Goa", "IN", "India"), Place("New Delhi", "Delhi", "IN", "India"), Place("Bengaluru", "Karnataka", "IN", "India")),
        ) { placeIds[it].orEmpty() },
        NamedSource(MatchKind.Album, listOf(Named("WhatsApp Images", partsOf("WhatsApp Images")) { setOf(10L) })),
        typeSource,
        textSource { prefix -> textIds.filterKeys { it.startsWith(prefix) }.values.flatten().toSet() },
        fileSource,
    )

    private fun terms(query: String) = parseQuery(query, dates, sources)
    private fun labels(query: String) = terms(query).map { it.label }

    private fun ids(query: String, items: List<SearchItem>) = terms(query).let { t -> matching(items, t).map { items[it].id } }

    @Test fun pluralsMeetTheirSingulars() {
        listOf("dog" to "dogs", "beach" to "beaches", "puppy" to "puppies", "selfie" to "selfies", "sky" to "skies", "glass" to "glasses", "bus" to "buses")
            .forEach { (one, many) -> assertEquals(many, singular(one), singular(many)) }
        assertEquals("paris", singular("paris")) // not a plural
    }

    @Test fun wordsDropStopWordsAccentsAndPunctuation() {
        assertEquals(listOf("dogs", "goa"), wordsOf("Dogs in Goa!").map { it.raw })
        assertEquals("sao paulo", keyOf("São Paulo"))
        assertEquals(listOf("img_2024"), wordsOf("  img_2024, ").map { it.raw })
    }

    @Test fun readsThingsPlacesAndDatesTogether() {
        assertEquals(listOf("Dog", "Goa", "March 2024"), labels("dogs in goa march 2024"))
        assertEquals(listOf("New Delhi"), labels("new delhi")) // a two-word place beats "new" and "delhi"
        assertEquals(listOf("Delhi"), labels("delhi"))
    }

    @Test fun categoriesAnswerToTheirTitleAndSynonyms() {
        assertEquals(listOf("Pets"), labels("pets")) // the category Pets and nothing else; "Pet" isn't a label here
        assertEquals(listOf("Dog"), labels("puppy"))
        assertEquals(listOf("Food"), labels("food")) // a category and a label with one name
        assertEquals(listOf("Sky and sunsets"), labels("sky and sunsets"))
        assertEquals(listOf("Sky and sunsets"), labels("sunsets"))
        assertEquals(listOf("Skies"), labels("skies")) // the label Sky and the category: two names, so the word typed
        assertEquals(listOf("Hot dog"), labels("hot dogs")) // not "hot" and dogs
    }

    @Test fun placesAnswerToOtherNames() {
        assertEquals(listOf("Bengaluru"), labels("bangalore"))
    }

    @Test fun albumsAnswerToPartOfTheirName() {
        assertEquals(listOf("WhatsApp Images"), labels("whatsapp"))
        assertEquals(listOf(10L), ids("whatsapp", listOf(item(1), item(10))))
    }

    @Test fun unknownWordsSearchTextAndFileNames() {
        val items = listOf(item(8), item(9), item(11, name = "pxl_20240101.jpg"), item(1))
        assertEquals(listOf("“invo”"), labels("invo"))
        assertEquals(listOf(8L), ids("invo", items)) // text prefix
        assertEquals(listOf(11L), ids("pxl", items)) // file name
        assertEquals(listOf(1L, 9L), ids("goa", items).sorted()) // the place, or the word in a photo
    }

    @Test fun termsCombineWithAnd() {
        val items = listOf(item(1), item(2), item(3), item(4))
        assertEquals(listOf(1L, 2L), ids("pets", items))
        assertEquals(listOf(1L), ids("pets goa", items))
        assertEquals(emptyList<Long>(), ids("cat goa", items))
    }

    @Test fun namesWhatFoundNothing() {
        val items = listOf(item(1), item(2))
        assertEquals(listOf("“zzz”"), unmatched(items, terms("dog zzz")).map { it.label })
        assertEquals(emptyList<String>(), unmatched(items, terms("dog cat")).map { it.label }) // each alone finds something
    }

    @Test fun mediaTypes() {
        val items = listOf(item(1, video = true), item(2, screenshot = true), item(3, favorite = true))
        assertEquals(listOf(1L), ids("videos", items))
        assertEquals(listOf(2L, 3L), ids("photos", items))
        assertEquals(listOf(2L), ids("screenshots", items))
        assertEquals(listOf(3L), ids("favourites", items))
    }

    @Test fun dates() {
        val items = listOf(
            item(1, today),
            item(2, today.minusDays(1)),
            item(3, LocalDate.of(2026, 10, 14)), // the week before this one (Monday 19 to Sunday 25)
            item(4, LocalDate.of(2026, 9, 30)),
            item(5, LocalDate.of(2024, 3, 9)),
            item(6, LocalDate.of(2023, 3, 1)),
        )
        assertEquals(listOf(1L), ids("today", items))
        assertEquals(listOf(2L), ids("yesterday", items))
        assertEquals(listOf(1L, 2L), ids("this week", items))
        assertEquals(listOf(3L), ids("last week", items))
        assertEquals(listOf(4L), ids("last month", items))
        assertEquals(listOf(1L, 2L, 3L, 4L), ids("this year", items))
        assertEquals(listOf(5L), ids("2024", items))
        assertEquals(listOf(5L, 6L), ids("march", items))
        assertEquals(listOf(5L, 6L), ids("mar", items))
        assertEquals(listOf(5L), ids("march 2024", items))
        assertEquals(listOf(5L), ids("2024 mar", items))
        assertEquals(listOf("September"), labels("sept"))
        assertEquals(listOf("“ma”"), labels("ma")) // too short to be a month
    }

    @Test fun aYearIsOnlyAYear() {
        val receipt = item(1, LocalDate.of(2026, 1, 1), name = "receipt_2024.jpg")
        assertEquals(emptyList<Long>(), ids("2024", listOf(receipt)))
    }

    @Test fun onlyStopWordsMeanNoTerms() {
        assertEquals(emptyList<Term>(), terms("the and of"))
    }

    @Test fun recentSearchesKeepNewestFirstWithoutRepeats() {
        assertEquals(listOf("goa", "dogs"), withRecent(listOf("dogs"), " goa ", 8))
        assertEquals(listOf("Dogs", "goa"), withRecent(listOf("goa", "dogs"), "Dogs", 8))
        assertEquals(listOf("c", "a"), withRecent(listOf("a", "b"), "c", 2))
        assertEquals(listOf("a"), withRecent(listOf("a"), "  ", 8))
    }

    @Test fun namesReadNaturally() {
        assertEquals("Goa", namesOf(listOf("Goa")))
        assertEquals("Goa and Food", namesOf(listOf("Goa", "Food")))
        assertEquals("Goa, Food and 2024", namesOf(listOf("Goa", "Food", "2024")))
    }

    @Test fun answersWithinBudgetFor20kItems() {
        val items = List(20_000) { i -> item(i.toLong(), today.minusDays(i / 10L), video = i % 7 == 0, name = "img_$i.jpg") }
        val query = "dogs in goa march 2024 photos"
        repeat(5) { matching(items, terms(query)) } // warm up the JIT
        val started = System.nanoTime()
        matching(items, terms(query))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 100)
    }
}
