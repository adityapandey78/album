package com.aditya.album

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Search reads a query as terms, each the longest run of words something recognises ("new delhi", "march 2024", "dogs").
// A photo matches a term when any reading of it holds (photos in Goa, or "goa" in a screenshot), and the query when
// every term does. Pure Kotlin: the app plugs in the smart index, albums and collections as sources (SearchScreen.kt).

/** What search knows about one photo or video. Plain values, so the engine runs and is tested without Android. */
class SearchItem(
    val id: Long,
    val day: Long, // local epoch day it was taken
    val isVideo: Boolean,
    val isFavorite: Boolean,
    val isScreenshot: Boolean,
    val name: String, // file name, lower case
)

/** What kind of reading a match is; its chip shows this as an icon. */
enum class MatchKind { Thing, Place, Date, Album, Type, Text, Person }

/** One reading of a phrase. [label] is what its chip says ("Goa", "Food", "March 2024"); [test] picks the photos. */
class Match(val label: String, val kind: MatchKind, val test: (SearchItem) -> Boolean)

/**
 * A word of a query or a name. [raw] is as typed, lower case. [plain] has no accents or punctuation ("são" is "sao").
 * [key] also folds plurals: names are compared by it.
 */
class Word(val raw: String, val plain: String, val key: String)

/**
 * Something a phrase can name. The app's sources are things, places, albums and collections, media types, text in
 * photos and file names; People can plug in as one more [NamedSource]. Returns null when the phrase means nothing to it.
 */
fun interface SearchSource {
    fun match(words: List<Word>): Match?
}

/** A phrase of the query and every source's reading of it. */
class Term(val words: List<Word>, val matches: List<Match>) {
    val text get() = words.joinToString(" ") { it.raw }

    private val named get() = matches.firstOrNull { it.kind != MatchKind.Text }

    /** What its chip says: the name it was recognised as, else the words themselves (found in text or file names). */
    val label get() = named?.label ?: "“$text”"
    val kind get() = named?.kind ?: MatchKind.Text

    fun test(item: SearchItem) = matches.any { it.test(item) }
}

private const val MAX_PHRASE = 4 // words: "united arab emirates", "selfies people" (stop words are dropped)

// Never matched on their own: "dogs in goa" means dogs and Goa.
private val stopWords = setOf("a", "an", "the", "and", "of", "in", "at", "on", "from", "with", "my", "to", "for", "during", "taken")
private val separators = Regex("[\\s,;]+")
private val accents = Regex("\\p{Mn}+")

/** The words of a query or a name, without stop words. */
fun wordsOf(text: String): List<Word> = text.lowercase().split(separators).mapNotNull { token ->
    val raw = token.trim { !it.isLetterOrDigit() }
    val plain = Normalizer.normalize(raw, Normalizer.Form.NFD).replace(accents, "").filter { it.isLetterOrDigit() }
    if (plain.isEmpty() || raw in stopWords) null else Word(raw, plain, singular(plain))
}

/** A name as one comparable key: "Sky and sunsets" is "ski sunset". */
fun keyOf(name: String) = wordsOf(name).joinToString(" ") { it.key }

/**
 * Folds English plurals, so "dogs", "beaches", "puppies" and "selfies" meet "dog", "beach", "puppy" and "selfie". Names and
 * queries go through it alike, so it only has to agree with itself: "sky" and "skies" both become "ski".
 */
internal fun singular(word: String): String {
    var w = word
    if (w.length > 3 && w.endsWith('s') && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) w = w.dropLast(1)
    if (w.length > 3 && w.endsWith('e')) w = w.dropLast(1)
    if (w.length >= 3 && w.endsWith('y')) w = w.dropLast(1) + 'i'
    return w
}

/** Every run of words in [name], so an album or a person answers to part of it ("whatsapp" finds WhatsApp Images). */
fun partsOf(name: String): List<String> {
    val keys = wordsOf(name).map { it.key }
    return keys.indices.flatMap { from -> (from + 1..keys.size).map { to -> keys.subList(from, to).joinToString(" ") } }
}

/**
 * Splits [query] into terms. From each word it takes the longest phrase anything recognises, else that word alone.
 * A date is only a date: "2024" means the year, not "2024" printed on a receipt.
 */
fun parseQuery(query: String, dates: SearchSource, sources: List<SearchSource>): List<Term> {
    val words = wordsOf(query)
    val terms = ArrayList<Term>()
    var at = 0
    while (at < words.size) {
        val term = (minOf(MAX_PHRASE, words.size - at) downTo 1).firstNotNullOf { n ->
            val phrase = words.subList(at, at + n)
            val matches = dates.match(phrase)?.let(::listOf) ?: sources.mapNotNull { it.match(phrase) }
            if (matches.isNotEmpty() || n == 1) Term(phrase, matches) else null
        }
        terms += term
        at += term.words.size
    }
    return terms
}

/** Indexes of the [items] matching every term, in [items]' order. */
fun matching(items: List<SearchItem>, terms: List<Term>): List<Int> = items.indices.filter { i -> terms.all { it.test(items[i]) } }

/** The terms that match nothing even on their own: what an empty result should name. */
fun unmatched(items: List<SearchItem>, terms: List<Term>): List<Term> = terms.filter { term -> items.none(term::test) }

// --- Sources

/** A set of photos with a name: a category, an ML Kit label, a place, an album, a person. [ids] runs only once it matches. */
class Named(val name: String, val keys: Collection<String>, val ids: () -> Set<Long>)

/** Matches a phrase to every [Named] with that key. Several can share one ("food" is a category and a label): all count. */
class NamedSource(private val kind: MatchKind, named: List<Named>) : SearchSource {
    private val byKey = HashMap<String, MutableList<Named>>()

    init {
        for (n in named) for (key in n.keys.toSet()) byKey.getOrPut(key) { ArrayList() } += n
    }

    override fun match(words: List<Word>): Match? {
        val found = byKey[words.joinToString(" ") { it.key }] ?: return null
        val ids = found.flatMapTo(HashSet()) { it.ids() }
        // One name shows as itself ("bangalore" finds Bengaluru); several (Sky and sunsets, Sky) as the words typed
        val label = found.map { it.name }.distinct().singleOrNull() ?: words.joinToString(" ") { it.raw }.replaceFirstChar { it.uppercase() }
        return Match(label, kind) { it.id in ids }
    }
}

/** Everyday words for ML Kit labels and categories, keyed by the label or the [Category] name they stand for. */
private val synonyms = mapOf(
    "Dog" to listOf("puppy", "doggy", "doggo"),
    "Cat" to listOf("kitten", "kitty"),
    "Food" to listOf("breakfast", "dinner", "snack"),
    "Celebrations" to listOf("birthday", "festival", "wedding"),
    "Beach" to listOf("sea", "ocean", "coast"),
    "Mountains" to listOf("hill", "trek"),
    "Sunset" to listOf("sunrise", "dusk", "dawn"),
    "Airplane" to listOf("plane", "aeroplane", "flight"),
    "Bicycle" to listOf("bike", "cycle"),
    "Motorcycle" to listOf("motorbike", "scooter"),
    "Rickshaw" to listOf("auto", "tuktuk"),
    "Boat" to listOf("ship", "ferry"),
    "Mobile phone" to listOf("phone"),
    "Television" to listOf("tv"),
    "Computer" to listOf("laptop"),
    "Receipt" to listOf("bill", "invoice"),
    "Documents" to listOf("doc", "note"),
    "Child" to listOf("kid"),
    "Baby" to listOf("infant", "toddler"),
    "Christmas" to listOf("xmas"),
    "Sari" to listOf("saree"),
)

private fun keysOf(words: List<String>?) = words.orEmpty().map(::keyOf)

/** Categories (by title, or any word of it: "sunsets") and ML Kit labels, each also by its [synonyms]. */
fun thingSource(
    categories: Collection<Category>,
    labels: Collection<String>,
    idsIn: (Category) -> Set<Long>,
    idsWith: (String) -> Set<Long>,
) = NamedSource(
    MatchKind.Thing,
    categories.filter { it != Category.Other }.map { c ->
        Named(c.title, wordsOf(c.title).map { it.key } + keyOf(c.title) + keysOf(synonyms[c.name])) { idsIn(c) }
    } + labels.map { label -> Named(label, listOf(keyOf(label)) + keysOf(synonyms[label])) { idsWith(label) } },
)

/** Other names people use for places, keyed by the GeoNames name. */
private val placeAliases = mapOf(
    "Bengaluru" to listOf("bangalore"),
    "Mumbai" to listOf("bombay"),
    "Kolkata" to listOf("calcutta"),
    "Chennai" to listOf("madras"),
    "Gurugram" to listOf("gurgaon"),
    "Mysuru" to listOf("mysore"),
    "Kochi" to listOf("cochin"),
    "Thiruvananthapuram" to listOf("trivandrum"),
    "Kozhikode" to listOf("calicut"),
    "Vadodara" to listOf("baroda"),
    "Prayagraj" to listOf("allahabad"),
    "Varanasi" to listOf("banaras", "benares"),
    "Puducherry" to listOf("pondicherry", "pondy"),
    "United States" to listOf("usa", "america"),
    "United Kingdom" to listOf("uk", "britain"),
    "United Arab Emirates" to listOf("uae"),
).mapKeys { keyOf(it.key) }

/** Towns, states and countries, by name or a common other name ("bangalore"). A name shared by a town and a state finds both. */
fun placeSource(places: Collection<Place>, idsAt: (String) -> Set<Long>) = NamedSource(
    MatchKind.Place,
    places.flatMap { listOf(it.city, it.admin1, it.country) }.filter { it.isNotEmpty() }.distinct().map { name ->
        val key = keyOf(name)
        Named(name, listOf(key) + keysOf(placeAliases[key])) { idsAt(name) }
    },
)

/** "videos", "photos", "screenshots", "favourites". */
val typeSource: SearchSource = run {
    val types = listOf(
        Match("Videos", MatchKind.Type) { it.isVideo } to listOf("video", "clip", "movie"),
        Match("Photos", MatchKind.Type) { !it.isVideo } to listOf("photo", "picture", "pic", "image"),
        Match("Screenshots", MatchKind.Type) { it.isScreenshot } to listOf("screenshot"),
        Match("Favourites", MatchKind.Type) { it.isFavorite } to listOf("favourite", "favorite", "fav"),
    )
    val byKey = types.flatMap { (match, words) -> words.map { keyOf(it) to match } }.toMap()
    SearchSource { words -> byKey[words.joinToString(" ") { it.key }] }
}

/** File names containing a word as typed, like "img_2024" or "pxl". Shorter words would match nearly every file. */
val fileSource = SearchSource { words ->
    val raw = words.singleOrNull()?.raw?.takeIf { it.length >= 3 } ?: return@SearchSource null
    Match("“$raw”", MatchKind.Text) { raw in it.name }
}

/** Words read in photos (screenshots, receipts) starting with the word typed, so "invo" already finds "invoice". */
fun textSource(idsWithText: (prefix: String) -> Set<Long>) = SearchSource { words ->
    val prefix = words.singleOrNull()?.plain?.takeIf { it.length >= 2 } ?: return@SearchSource null
    val ids = idsWithText(prefix)
    if (ids.isEmpty()) null else Match("“$prefix”", MatchKind.Text) { it.id in ids }
}

/**
 * Dates: "today", "yesterday", "this week" or "last month" (whole calendar weeks starting on [weekStart], months, years),
 * a year, a month of any year ("march", "sept") or a month of one year ("march 2024", "2024 mar").
 */
class DateSource(private val today: LocalDate, private val weekStart: DayOfWeek) : SearchSource {
    override fun match(words: List<Word>): Match? {
        val w = words.map { it.plain }
        return when (w.size) {
            1 -> when (w[0]) {
                "today" -> days("Today", today, today)
                "yesterday" -> today.minusDays(1).let { days("Yesterday", it, it) }
                else -> yearOf(w[0])?.let { days("$it", LocalDate.of(it, 1, 1), LocalDate.of(it, 12, 31)) }
                    ?: monthOf(w[0])?.let { month -> Match(titleOf(month), MatchKind.Date) { LocalDate.ofEpochDay(it.day).month == month } }
            }
            2 -> relative(w[0], w[1]) ?: monthOfYear(w[0], w[1]) ?: monthOfYear(w[1], w[0])
            else -> null
        }
    }

    private fun relative(which: String, unit: String): Match? {
        val ago = when (which) {
            "this" -> 0L
            "last" -> 1L
            else -> return null
        }
        val period = when (unit) {
            "week" -> ChronoUnit.WEEKS
            "month" -> ChronoUnit.MONTHS
            "year" -> ChronoUnit.YEARS
            else -> return null
        }
        val start = when (period) {
            ChronoUnit.WEEKS -> today.with(TemporalAdjusters.previousOrSame(weekStart))
            ChronoUnit.MONTHS -> today.withDayOfMonth(1)
            else -> today.withDayOfYear(1)
        }.minus(ago, period)
        return days("${which.replaceFirstChar { it.uppercase() }} $unit", start, start.plus(1, period).minusDays(1))
    }

    private fun monthOfYear(month: String, year: String): Match? {
        val m = monthOf(month) ?: return null
        val start = LocalDate.of(yearOf(year) ?: return null, m, 1)
        return days("${titleOf(m)} ${start.year}", start, start.plusMonths(1).minusDays(1))
    }

    private fun days(label: String, from: LocalDate, to: LocalDate): Match {
        val range = from.toEpochDay()..to.toEpochDay()
        return Match(label, MatchKind.Date) { it.day in range }
    }

    private fun yearOf(word: String) = word.takeIf { it.length == 4 }?.toIntOrNull()?.takeIf { it in 1900..2099 }

    // Three letters or more of a month's name: "mar", "sept", "december"
    private fun monthOf(word: String) = if (word.length < 3) null else Month.entries.firstOrNull { it.name.lowercase().startsWith(word) }

    private fun titleOf(month: Month) = month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
}
