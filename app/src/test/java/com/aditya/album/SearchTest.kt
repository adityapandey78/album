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

    // A small library: what the smart index knows, by id, with ML Kit's confidence
    private val categoryScores = mapOf(
        Category.Pets to mapOf(1L to 0.9f, 2L to 0.8f),
        Category.Food to mapOf(3L to 0.9f),
        Category.Sunsets to mapOf(4L to 0.7f),
    )
    private val labelScores = mapOf(
        "Dog" to mapOf(1L to 0.9f, 12L to 0.35f), // 12: a dog ML Kit is only fairly sure of
        "Cat" to mapOf(2L to 0.8f),
        "Food" to mapOf(3L to 0.9f, 5L to 0.6f),
        "Sky" to mapOf(4L to 0.8f, 6L to 0.5f),
        "Hot dog" to mapOf(7L to 0.7f),
        "Marriage" to mapOf(13L to 0.6f),
        "Sari" to mapOf(13L to 0.4f),
        "Cake" to mapOf(14L to 0.7f),
        "Party" to mapOf(15L to 0.5f),
        "Baby" to mapOf(16L to 0.8f),
        "Plant" to mapOf(17L to 0.6f),
        "Mobile phone" to mapOf(30L to 0.9f, 31L to 0.6f),
    )
    private val placeIds = mapOf(
        "Panjim" to setOf(1L, 3L), "Goa" to setOf(1L, 3L, 4L), "North Goa" to setOf(1L, 3L, 4L), "Baga" to setOf(1L),
        "New Delhi" to setOf(5L), "Delhi" to setOf(6L), "Bengaluru" to setOf(7L),
    )
    private val aliases = mapOf("Panjim" to listOf("Panaji", "Pangim"), "Bengaluru" to listOf("Bangalore"))
    // Words read in photos, as the text index splits them (ftsTokens): "e ticket" is how it holds "E-ticket"
    private val textIds = mapOf(
        "invoice" to setOf(8L), "goa" to setOf(9L), "tax" to setOf(8L, 18L), "red" to setOf(12L, 19L), "phonepe" to setOf(32L),
        "e ticket" to setOf(23L), "tromsø" to setOf(24L), "15 08 2024" to setOf(25L),
    )
    private val sources = listOf(
        thingSource(categoryScores.keys, labelScores.keys, { categoryScores[it].orEmpty() }, { labelScores[it].orEmpty() }),
        placeSource(placeIds.keys, aliases) { placeIds[it].orEmpty() },
        NamedSource(MatchKind.Album, listOf(Named("WhatsApp Images", partsOf("WhatsApp Images")) { setOf(10L) })),
        typeSource,
        textSource { tokens -> textIds.filterKeys { it.startsWith(tokens.joinToString(" ")) }.values.flatten().toSet() },
        fileSource,
    )
    private val exact = exactTextSource { tokens -> if (tokens == listOf("tax", "invoice")) setOf(8L) else textIds[tokens.joinToString(" ")].orEmpty() }

    private fun terms(query: String) = parseQuery(query, dates, sources, exact)
    private fun labels(query: String) = terms(query).map { it.label }

    private fun found(query: String, items: List<SearchItem>, described: List<Long>? = null) = find(items, terms(query), described)
    private fun ids(query: String, items: List<SearchItem>, described: List<Long>? = null) = found(query, items, described).let { f -> (f.top + f.more).map { items[it].id } }
    private fun top(query: String, items: List<SearchItem>, described: List<Long>? = null) = found(query, items, described).top.map { items[it].id }
    private fun more(query: String, items: List<SearchItem>, described: List<Long>? = null) = found(query, items, described).more.map { items[it].id }

    private val library = (1L..25L).map { item(it) }

    @Test fun pluralsMeetTheirSingulars() {
        listOf("dog" to "dogs", "beach" to "beaches", "puppy" to "puppies", "selfie" to "selfies", "sky" to "skies", "glass" to "glasses", "bus" to "buses")
            .forEach { (one, many) -> assertEquals(many, singular(one), singular(many)) }
        assertEquals("paris", singular("paris")) // not a plural
    }

    @Test fun wordsDropStopWordsAccentsAndPunctuation() {
        assertEquals(listOf("dogs", "goa"), wordsOf("Dogs in Goa!").map { it.raw })
        assertEquals("sao paulo", keyOf("São Paulo"))
        assertEquals("tromso", keyOf("Tromsø")) // a letter with nothing to strip, typed plainly
        assertEquals(listOf("img_2024"), wordsOf("  img_2024, ").map { it.raw })
        assertEquals(listOf("state", "bank", "of", "india"), wordsOf("State Bank of India", keepStopWords = true).map { it.raw })
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

    @Test fun everydayWordsFindMlKitLabels() {
        assertEquals(listOf("Marriage"), labels("wedding")) // ML Kit has no Wedding; Bride and Groom aren't in this library
        assertEquals(listOf(13L), ids("wedding", library))
        assertEquals(listOf("Birthday"), labels("birthday")) // Cake and Party: two names, so the word typed
        assertEquals(listOf(14L, 15L), ids("birthday", library))
        assertEquals(listOf("Sari"), labels("saree"))
        assertEquals(listOf(16L), ids("kids", library)) // Baby: ML Kit has no Child
        assertEquals(listOf(17L), ids("trees", library)) // Plant: it has no Tree
    }

    @Test fun placesAnswerToOtherNamesAndEveryLevel() {
        assertEquals(listOf("Bengaluru"), labels("bangalore")) // Album's own list
        assertEquals(listOf("Panjim"), labels("panaji")) // GeoNames' other names
        assertEquals(listOf("Panjim"), labels("pangim"))
        assertEquals(listOf("Baga"), labels("baga")) // a locality
        assertEquals(listOf("North Goa"), labels("north goa")) // a district
        assertEquals(listOf(1L, 3L, 4L), ids("north goa", library))
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
        assertEquals(listOf(9L, 1L), ids("goa", items)) // the place, or the word in a photo
    }

    @Test fun placesDatesAndThingsCombineWithAnd() {
        assertEquals(listOf(1L, 2L), ids("pets", library))
        assertEquals(listOf(1L), ids("pets goa", library))
        assertEquals(emptyList<Long>(), ids("cat goa", library)) // a cat and Goa, but not together
    }

    @Test fun aWordNothingKnowsNoLongerEmptiesTheSearch() {
        assertEquals(listOf(1L, 3L, 4L, 9L), ids("goa zzz", library))
        assertEquals(listOf("“zzz”"), found("goa zzz", library).ignored.map { it.label })
        assertEquals(listOf(1L, 12L), ids("dog zzz", library))
        assertEquals(emptyList<Long>(), ids("zzz", library)) // only words nothing knows: nothing to show
        assertEquals(listOf("“zzz”"), unmatched(library, terms("zzz")).map { it.label })
        assertEquals("“zzz” matched nothing, so it's left out", leftOut(found("goa zzz", library).ignored))
    }

    @Test fun anEmptyResultNamesWhatFoundNothing() {
        fun nothing(query: String) = terms(query).let { t -> unmatched(library, t, find(library, t)).map { it.label } }
        assertEquals(listOf("“zzz”"), nothing("zzz")) // all there was
        assertEquals(emptyList<String>(), nothing("cat goa zzz")) // Cat and Goa each find photos, just not together
        assertEquals(listOf("“zzz”"), nothing("goa \"zzz\"")) // quoted text something could know, but no photo has
        assertEquals(emptyList<String>(), nothing("goa")) // not empty
    }

    @Test fun looseWordsRankWithinThingsButNeverNarrowThem() {
        assertEquals(listOf(12L), top("red dog", library)) // the dog with "red" in it goes first...
        assertEquals(listOf(1L), more("red dog", library)) // ...the surer dog still shows; 19 has "red" but no dog
    }

    @Test fun labelsRankByConfidence() {
        val dogs = (1L..20L).map { item(it) }
        val sure = (1L..20L).associateWith { 1f - it / 40f }.toMutableMap().apply { put(20L, 0.99f) } // 20 is the surest
        val source = thingSource(emptyList(), listOf("Dog"), { emptyMap() }) { sure }
        val found = find(dogs, parseQuery("dog", dates, listOf(source)))
        assertEquals(listOf(20L) + (1L..11L), found.top.map { dogs[it].id }) // the 12 surest, surest first
        assertEquals((12L..19L).toList(), found.more.map { dogs[it].id }) // the rest in the library's order (newest first)
    }

    @Test fun onlyNarrowingTermsMeanNoTopMatches() {
        assertEquals(emptyList<Long>(), top("goa", library))
        assertEquals(listOf(1L, 3L, 4L, 9L), more("goa", library))
    }

    @Test fun theModelAddsPhotosLabelsMissedAndRanksWithThem() {
        assertEquals(listOf(1L, 12L, 20L), ids("dog", library, described = listOf(20L, 1L)).sorted())
        assertEquals(listOf(1L), top("dog", library, described = listOf(20L, 1L))) // both ML Kit and the model chose it
    }

    @Test fun photosOnlyTheModelFoundGoAfterExactMatches() {
        assertEquals(listOf(1L, 12L), top("dog", library, described = listOf(20L))) // even ML Kit's unsure dog beats the model's guess
        assertEquals(listOf(20L), more("dog", library, described = listOf(20L)))
        assertEquals(listOf(8L), top("invoice", library, described = listOf(21L))) // the word in the photo first
        assertEquals(listOf(21L), more("invoice", library, described = listOf(21L)))
    }

    @Test fun theModelAloneFindsDescriptions() {
        assertEquals(listOf(21L, 22L), top("people dancing", library, described = listOf(21L, 22L)))
        assertEquals(emptyList<Long>(), ids("people dancing", library, described = null)) // no model: nothing knows the words
    }

    @Test fun theModelStaysInsideTheNarrowing() {
        assertEquals(listOf(1L), ids("goa zzz", library, described = listOf(5L, 1L))) // 5 isn't in Goa
        assertEquals(listOf(1L, 3L, 4L, 9L), ids("goa zzz", library, described = listOf(5L))) // nothing in Goa: the word is left out
    }

    @Test fun quotedTextIsExact() {
        assertEquals(listOf("“tax invoice”"), labels("\"tax invoice\""))
        assertEquals(listOf(8L), ids("\"tax invoice\"", library))
        assertEquals(listOf(9L), ids("“goa”", library)) // only the word in a photo, not photos taken in Goa
        assertEquals(listOf("“tax invoice”"), labels("\"tax invoice")) // an unclosed quote while typing
        assertEquals(listOf("Goa", "“tax invoice”"), labels("goa \"tax invoice\""))
        assertEquals(emptyList<Long>(), ids("\"zzz\"", library)) // quoted text narrows, even to nothing
    }

    @Test fun thingsRankByTheirLabelsNotTheWordInScreenshots() {
        val items = library + listOf(item(30), item(31), item(32), item(33, name = "phone_case.jpg"))
        assertEquals(listOf(30L, 31L), top("phone", items)) // photos of phones
        assertEquals(listOf(32L, 33L), more("phone", items)) // "PhonePe" in a screenshot, a file named phone: still found
    }

    @Test fun textIsSearchedTheWayTheIndexSplitsIt() {
        assertEquals(listOf("e", "ticket"), ftsTokens("E-ticket"))
        assertEquals(listOf("15", "08", "2024"), ftsTokens("15/08/2024"))
        assertEquals(listOf("tromsø"), ftsTokens("Tromsø")) // the index keeps ø, ß and æ...
        assertEquals(listOf("cafe"), ftsTokens("Café")) // ...and drops accents
        assertEquals(listOf("cafe"), ftsTokens("Cafe\u0301")) // however they're typed
        assertEquals(listOf("don", "t"), ftsTokens("don't"))
        assertEquals(listOf(23L), ids("\"e-ticket\"", library))
        assertEquals(listOf(25L), ids("\"15/08/2024\"", library))
        assertEquals(listOf(23L), ids("e-tick", library)) // the last part may be the start of a word
        assertEquals(listOf(24L), ids("tromsø", library))
    }

    @Test fun photosWithEveryWordRankFirst() {
        assertEquals(listOf(8L), top("tax invoice", library)) // both words in it
        assertEquals(listOf(18L), more("tax invoice", library)) // one
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

    @Test fun daysOfTheMonth() {
        val items = listOf(item(1, LocalDate.of(2024, 8, 15)), item(2, LocalDate.of(2025, 8, 15)), item(3, LocalDate.of(2025, 8, 16)))
        assertEquals(listOf("15 August"), labels("15 august"))
        assertEquals(listOf(1L, 2L), ids("15 august", items)) // every year
        assertEquals(listOf(1L, 2L), ids("aug 15th", items))
        assertEquals(listOf("15 August 2024"), labels("15th aug 2024"))
        assertEquals(listOf(1L), ids("15 aug 2024", items))
        assertEquals(listOf("“31”", "February"), labels("31 february")) // no such day
    }

    @Test fun aYearIsOnlyAYear() {
        val receipt = item(1, LocalDate.of(2026, 1, 1), name = "receipt_2024.jpg")
        assertEquals(emptyList<Long>(), ids("2024", listOf(receipt)))
    }

    @Test fun onlyStopWordsMeanNoTerms() {
        assertEquals(emptyList<Term>(), terms("the and of"))
        assertTrue(find(library, terms("the and of")).isEmpty)
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
        val dogs = (0L until 20_000L step 3).associateWith { (it % 97) / 97f } // a big ranked list to fuse
        val sources = listOf(thingSource(emptyList(), listOf("Dog"), { emptyMap() }) { dogs }) + sources.drop(1)
        val query = "dogs in goa march 2024 photos red"
        fun run() = find(items, parseQuery(query, dates, sources, exact), described = (0L until 500L).toList())
        repeat(5) { run() } // warm up the JIT
        val started = System.nanoTime()
        run()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 100)
    }

    @Test fun wordsInPhotosAreTheOnesFoundMostFirst() {
        val counts = mapOf("invoice" to 3, "receipt" to 0, "ticket" to 7, "menu" to 1)
        assertEquals(listOf("ticket", "invoice"), wordsInPhotos(listOf("invoice", "receipt", "ticket", "menu"), max = 2) { counts.getValue(it) })
        assertEquals(emptyList<String>(), wordsInPhotos(listOf("receipt"), max = 8) { 0 })
    }
}
