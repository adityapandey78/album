package com.aditya.album

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Search reads a query as terms, each the longest run of words something recognises ("new delhi", "march 2024", "dogs"),
// plus quoted text, which only matches those words in a row in a photo. Places, dates, people, albums, types and quoted
// text narrow the results; so do things (labels), unless the describe-it model found the photo for the same words; words
// only text in photos or file names know, or nothing knows, rank the results but never empty them. Every reading that
// can rank (labels by confidence, the model by similarity at half weight, words found) is fused with reciprocal rank
// fusion into "Top matches"; the rest stay newest first. Pure Kotlin: the app plugs in the smart index, albums and collections as
// sources (SearchScreen.kt).

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

/**
 * One reading of a phrase. [label] is what its chip says ("Goa", "Food", "March 2024"); [test] picks the photos.
 * [ranked] lists the photos it picks best first, when it knows how sure it is of each (ML Kit's confidence).
 */
class Match(val label: String, val kind: MatchKind, val ranked: List<Long>? = null, val test: (SearchItem) -> Boolean)

/**
 * A word of a query or a name. [raw] is as typed, lower case. [plain] has no accents or punctuation ("são" is "sao").
 * [key] also folds plurals: names are compared by it.
 */
class Word(val raw: String, val plain: String, val key: String)

/**
 * Something a phrase can name. The app's sources are things, places, people, albums and collections, media types, text
 * in photos and file names. Returns null when the phrase means nothing to it.
 */
fun interface SearchSource {
    fun match(words: List<Word>): Match?
}

/** A phrase of the query and every source's reading of it. [quoted] text only matches those words in a row in a photo. */
class Term(val words: List<Word>, val matches: List<Match>, val quoted: Boolean = false) {
    val text get() = words.joinToString(" ") { it.raw }

    private val named get() = matches.firstOrNull { it.kind != MatchKind.Text }

    /** What its chip says: the name it was recognised as, else the words themselves (found in text or file names). */
    val label get() = named?.label ?: "“$text”"
    val kind get() = named?.kind ?: MatchKind.Text

    /** Narrows the results to what it picks: a place, date, person, album, media type or quoted text. */
    val narrows get() = quoted || matches.any { it.kind != MatchKind.Text && it.kind != MatchKind.Thing }

    /** A thing (food, dogs): narrows the results too, but the describe-it model can add photos ML Kit missed. */
    val isThing get() = !narrows && matches.any { it.kind == MatchKind.Thing }

    /** Words only text in photos or file names know, or nothing: they rank the results and never empty them. */
    val loose get() = !narrows && !isThing

    fun test(item: SearchItem) = matches.any { it.test(item) }
}

private const val MAX_PHRASE = 4 // words: "united arab emirates", "selfies people" (stop words are dropped)

// Never matched on their own: "dogs in goa" means dogs and Goa.
private val stopWords = setOf("a", "an", "the", "and", "of", "in", "at", "on", "from", "with", "my", "to", "for", "during", "taken")
private val separators = Regex("[\\s,;]+")
private val accents = Regex("\\p{Mn}+")
private val quotes = Regex("[\"“”]([^\"“”]*)(?:[\"“”]|$)") // an unclosed quote runs to the end, so search follows typing

// Letters without an accent to strip that people type plainly: Tromsø as tromso, Łódź as lodz
private val plainLetters = mapOf('ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss", 'ł' to "l", 'đ' to "d", 'ı' to "i", 'þ' to "th", 'ð' to "d")

/** The words of a query or a name, without stop words unless [keepStopWords] (quoted text is matched as written). */
fun wordsOf(text: String, keepStopWords: Boolean = false): List<Word> = text.lowercase().split(separators).mapNotNull { token ->
    val raw = token.trim { !it.isLetterOrDigit() }
    val stripped = Normalizer.normalize(raw, Normalizer.Form.NFD).replace(accents, "")
    val plain = buildString { for (c in stripped) if (c.isLetterOrDigit()) append(plainLetters[c] ?: c) }
    if (plain.isEmpty() || (!keepStopWords && raw in stopWords)) null else Word(raw, plain, singular(plain))
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
 * Splits [query] into terms, in the order typed. Quoted text becomes one term that only [exact] reads. Elsewhere, from
 * each word it takes the longest phrase anything recognises, else that word alone. A date is only a date: "2024" means
 * the year, not "2024" printed on a receipt.
 */
fun parseQuery(query: String, dates: SearchSource, sources: List<SearchSource>, exact: SearchSource? = null): List<Term> {
    val terms = ArrayList<Term>()
    var at = 0
    for (quote in quotes.findAll(query)) {
        terms += phrases(query.substring(at, quote.range.first), dates, sources)
        val words = wordsOf(quote.groupValues[1], keepStopWords = true)
        if (words.isNotEmpty()) terms += Term(words, listOfNotNull(exact?.match(words)), quoted = true)
        at = quote.range.last + 1
    }
    terms += phrases(query.substring(at), dates, sources)
    return terms
}

private fun phrases(text: String, dates: SearchSource, sources: List<SearchSource>): List<Term> {
    val words = wordsOf(text)
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

// --- Finding and ranking

/** Reciprocal rank fusion's k: a photo's score from a reading is 1 / (k + its rank there). 60 is the usual value. */
private const val RRF_K = 60

/** "Top matches" holds at most this many photos, each scoring at least [TOP_SHARE] of the best one. */
private const val TOP_MAX = 12
private const val TOP_SHARE = 0.75

// The describe-it model's ranks count half: about 2 unrelated photos in 70 pass its floor (4 for things no photo shows),
// so a photo only it found goes after the labels' and the words' own, and one it agrees on goes first.
private const val MODEL_WEIGHT = 0.5

/**
 * What a search found, as indexes into the items: [top] best first, [more] in the items' own order (newest first), and
 * the [ignored] terms: words nothing knows, left out instead of emptying the results.
 */
class Found(val top: List<Int>, val more: List<Int>, val ignored: List<Term>) {
    val isEmpty get() = top.isEmpty() && more.isEmpty()
}

/**
 * The [items] matching [terms]. [described] is what the describe-it model found for the query's words, best first, or
 * null without the model. Narrowing terms all hold; things all hold too, or the model found the photo; loose words that
 * something knows pick photos only when there's no thing to, and words nothing knows are left out. Then every reading
 * that can rank is fused: the photos that score well across them go first.
 */
fun find(items: List<SearchItem>, terms: List<Term>, described: List<Long>? = null): Found {
    if (terms.isEmpty()) return Found(emptyList(), emptyList(), emptyList())
    val narrowing = terms.filter { it.narrows }
    val things = terms.filter { it.isThing }
    val loose = terms.filter { it.loose }
    val model = described.orEmpty().toHashSet()
    val known = loose.filter { term -> items.any(term::test) } // in some photo's text or file name
    var ignored = if (model.isEmpty()) loose - known.toSet() else emptyList()
    fun narrowed(item: SearchItem) = narrowing.all { it.test(item) }
    var picked = items.indices.filter { i ->
        val item = items[i]
        narrowed(item) && when {
            things.isNotEmpty() -> things.all { it.test(item) } || item.id in model
            known.isNotEmpty() || model.isNotEmpty() -> known.any { it.test(item) } || item.id in model
            else -> narrowing.isNotEmpty() // only words nothing knows: nothing to show
        }
    }
    // Loose words that would narrow the rest to nothing are left out too: "goa zzz" shows Goa
    if (picked.isEmpty() && things.isEmpty() && loose.isNotEmpty() && narrowing.isNotEmpty()) {
        picked = items.indices.filter { narrowed(items[it]) }
        ignored = loose
    }
    return ranked(items, picked, things, loose, described, ignored)
}

/**
 * Fuses the ranking readings over [picked] into top matches and the rest: [things] by their labels' confidence, the
 * model by similarity, [loose] words by being found at all.
 */
private fun ranked(
    items: List<SearchItem>,
    picked: List<Int>,
    things: List<Term>,
    loose: List<Term>,
    described: List<Long>?,
    ignored: List<Term>,
): Found {
    val lists = things.flatMap { it.matches }.mapNotNull { it.ranked }.map { it to 1.0 } +
        listOfNotNull(described?.takeIf { it.isNotEmpty() }?.to(MODEL_WEIGHT))
    // A loose word found in a photo's text or name ranks every photo it picks first. A thing's own such readings don't
    // rank: they'd put "PhonePe" screenshots and files named phone above photos of phones.
    val ties = loose.flatMap { it.matches }.filter { it.ranked == null }
    if (lists.isEmpty() && ties.size < 2) return Found(emptyList(), picked, ignored) // nothing to tell photos apart
    val slot = HashMap<Long, Int>(picked.size * 2)
    picked.forEachIndexed { k, i -> slot[items[i].id] = k }
    val score = DoubleArray(picked.size)
    for ((list, weight) in lists) {
        var rank = 0
        for (id in list) {
            val k = slot[id] ?: continue // ranks count among the photos shown, not the ones narrowed out
            score[k] += weight / (RRF_K + ++rank)
        }
    }
    for (tie in ties) for (k in picked.indices) if (tie.test(items[picked[k]])) score[k] += 1.0 / (RRF_K + 1)
    val best = score.maxOrNull() ?: 0.0
    if (best == 0.0 || score.all { it == best }) return Found(emptyList(), picked, ignored)
    val order = picked.indices.sortedWith(compareByDescending<Int> { score[it] }.thenBy { it }) // equal scores: newest first
    val top = order.takeWhile { score[it] >= best * TOP_SHARE }.take(TOP_MAX)
    val inTop = top.toHashSet()
    return Found(top.map { picked[it] }, picked.filterIndexed { k, _ -> k !in inTop }, ignored)
}

/** The terms that match nothing even on their own. */
fun unmatched(items: List<SearchItem>, terms: List<Term>): List<Term> = terms.filter { term -> items.none(term::test) }

/**
 * What an empty result should name: the terms something knows that match nothing even on their own ("Nothing found for
 * Cat"), none when they only miss each other ("Nothing matches Cat and Goa together"), or the words nothing knows when
 * that's all there was.
 */
fun unmatched(items: List<SearchItem>, terms: List<Term>, found: Found): List<Term> {
    if (!found.isEmpty) return emptyList()
    val known = terms - found.ignored.toSet()
    return unmatched(items, known).ifEmpty { if (known.isEmpty()) terms else emptyList() }
}

// --- Sources

/**
 * A set of photos with a name: a category, an ML Kit label, a place, an album, a person. [ids] runs only once it
 * matches; a source that knows how sure it is of each photo gives [scores] instead (0 to 1), and its ids are their keys.
 */
class Named(
    val name: String,
    val keys: Collection<String>,
    val scores: (() -> Map<Long, Float>)? = null,
    val ids: () -> Set<Long> = { scores?.invoke()?.keys.orEmpty() },
)

/** Matches a phrase to every [Named] with that key. Several can share one ("food" is a category and a label): all count. */
class NamedSource(private val kind: MatchKind, named: List<Named>) : SearchSource {
    private val byKey = HashMap<String, MutableList<Named>>()

    init {
        for (n in named) for (key in n.keys.toSet()) byKey.getOrPut(key) { ArrayList() } += n
    }

    override fun match(words: List<Word>): Match? {
        val found = byKey[words.joinToString(" ") { it.key }] ?: return null
        val ids = HashSet<Long>()
        val scores = HashMap<Long, Float>()
        for (n in found) {
            val s = n.scores?.invoke()
            if (s == null) ids += n.ids() else s.forEach { (id, score) -> scores.merge(id, score, ::maxOf) }
        }
        ids += scores.keys
        // One name shows as itself ("bangalore" finds Bengaluru); several (Sky and sunsets, Sky) as the words typed
        val label = found.map { it.name }.distinct().singleOrNull() ?: words.joinToString(" ") { it.raw }.replaceFirstChar { it.uppercase() }
        val ranked = if (scores.isEmpty()) null else scores.entries.sortedByDescending { it.value }.map { it.key }
        return Match(label, kind, ranked) { it.id in ids }
    }
}

/**
 * Everyday words for ML Kit labels and categories, keyed by the label or the [Category] name they stand for. ML Kit has
 * no Wedding, Birthday, Saree, Kid or Tree, but it has Marriage, Cake, Sari, Baby and Plant (its label map:
 * developers.google.com/ml-kit/vision/image-labeling/label-map). One word may stand for several labels.
 */
private val synonyms = mapOf(
    // Categories
    "Food" to listOf("breakfast", "dinner", "snack", "khana"),
    "Celebrations" to listOf("festival", "celebration", "function"),
    "Beach" to listOf("sea", "ocean", "coast", "seaside"),
    "Mountains" to listOf("hill", "hills", "trek", "trekking", "himalaya"),
    "Documents" to listOf("doc", "document", "note", "notes", "certificate"),
    "Sports" to listOf("cricket", "game"),
    "City" to listOf("architecture", "street"),
    // People and occasions
    "Marriage" to listOf("wedding", "shaadi"),
    "Bride" to listOf("wedding", "shaadi", "dulhan"),
    "Groom" to listOf("wedding", "shaadi", "dulha"),
    "Cake" to listOf("birthday", "bday", "dessert", "pastry"),
    "Party" to listOf("birthday", "bday"),
    "Balloon" to listOf("birthday", "bday"),
    "Baby" to listOf("infant", "toddler", "newborn", "kid", "child", "children"),
    "Grandparent" to listOf("grandma", "grandpa", "grandmother", "grandfather", "nani", "dadi"),
    "Sari" to listOf("saree"),
    "Graduation" to listOf("convocation"),
    "Mortarboard" to listOf("convocation"),
    "Concert" to listOf("gig"),
    "Christmas" to listOf("xmas"),
    "Fireworks" to listOf("diwali", "crackers", "firecracker"),
    "Sparkler" to listOf("diwali"),
    "Temple" to listOf("mandir", "gurudwara", "pagoda"),
    "Mosque" to listOf("masjid", "dargah"),
    "Church" to listOf("chapel"),
    // Nature
    "Plant" to listOf("tree"),
    "Forest" to listOf("tree", "woods"),
    "Branch" to listOf("tree"),
    "Skiing" to listOf("snow"),
    "Snowboarding" to listOf("snow"),
    "Sledding" to listOf("snow"),
    "Glacier" to listOf("snow"),
    "Icicle" to listOf("snow"),
    "Sunset" to listOf("sunrise", "dusk", "dawn"),
    "Waterfall" to listOf("falls"),
    "Camping" to listOf("tent", "camp"),
    "Bonfire" to listOf("campfire"),
    // Animals
    "Dog" to listOf("puppy", "doggy", "doggo", "pup"),
    "Cat" to listOf("kitten", "kitty"),
    "Bird" to listOf("pigeon", "parrot", "crow", "sparrow", "peacock"),
    "Cattle" to listOf("cow", "buffalo"),
    "Horse" to listOf("pony"),
    "Aquarium" to listOf("fish"),
    "Underwater" to listOf("fish"),
    // Getting around
    "Airplane" to listOf("plane", "aeroplane", "flight"),
    "Airliner" to listOf("plane", "aeroplane", "flight"),
    "Bicycle" to listOf("bike", "cycle"),
    "Motorcycle" to listOf("bike", "motorbike", "scooter", "scooty"),
    "Car" to listOf("taxi", "cab", "jeep"),
    "Rickshaw" to listOf("auto", "tuktuk"),
    "Train" to listOf("railway", "metro"),
    "Boat" to listOf("ship", "ferry", "cruise", "houseboat", "shikara"),
    // Things
    "Mobile phone" to listOf("phone", "mobile", "smartphone"),
    "Television" to listOf("tv"),
    "Computer" to listOf("laptop", "pc"),
    "Receipt" to listOf("bill", "invoice"),
    "Money" to listOf("cash", "currency", "rupee"),
    "Jewellery" to listOf("jewelry", "jewels"),
    "Shoe" to listOf("footwear"),
    "Stuffed toy" to listOf("teddy"),
    "Statue" to listOf("idol", "sculpture"),
    "Palace" to listOf("fort", "mahal"),
    "Castle" to listOf("fort"),
    "Monument" to listOf("landmark", "memorial"),
    "Pool" to listOf("swimming pool"),
    "Soccer" to listOf("football"),
    // Food and drink
    "Gelato" to listOf("ice cream", "icecream", "kulfi"),
    "Cheeseburger" to listOf("burger"),
    "Fast food" to listOf("burger", "fries"),
    "Coffee" to listOf("cafe", "latte"),
    "Alcohol" to listOf("beer", "drinks", "whisky"),
)

private fun keysOf(words: List<String>?) = words.orEmpty().map(::keyOf)

/**
 * Categories (by title, or any word of it: "sunsets") and ML Kit labels, each also by its [synonyms], ranked by how sure
 * ML Kit was: [scoredIn] gives a category's photos with their strongest label of it, [scoredWith] a label's.
 */
fun thingSource(
    categories: Collection<Category>,
    labels: Collection<String>,
    scoredIn: (Category) -> Map<Long, Float>,
    scoredWith: (String) -> Map<Long, Float>,
) = NamedSource(
    MatchKind.Thing,
    categories.filter { it != Category.Other }.map { c ->
        Named(c.title, wordsOf(c.title).map { it.key } + keyOf(c.title) + keysOf(synonyms[c.name]), scores = { scoredIn(c) })
    } + labels.map { label -> Named(label, listOf(keyOf(label)) + keysOf(synonyms[label]), scores = { scoredWith(label) }) },
)

/** Other names people use for places, keyed by the GeoNames name, besides GeoNames' own (geonames/aliases.tsv). */
private val placeAliases = mapOf(
    "Bengaluru" to listOf("bangalore"),
    "Mumbai" to listOf("bombay"),
    "Kolkata" to listOf("calcutta"),
    "Chennai" to listOf("madras"),
    "Gurugram" to listOf("gurgaon"),
    "Mysuru" to listOf("mysore"),
    "Kochi" to listOf("cochin", "ernakulam"),
    "Thiruvananthapuram" to listOf("trivandrum"),
    "Kozhikode" to listOf("calicut"),
    "Vadodara" to listOf("baroda"),
    "Prayagraj" to listOf("allahabad"),
    "Varanasi" to listOf("banaras", "benares", "kashi"),
    "Puducherry" to listOf("pondicherry", "pondy"),
    "Panjim" to listOf("panaji"),
    "Kanniyākumāri" to listOf("kanyakumari"),
    "Odisha" to listOf("orissa"),
    "Uttarakhand" to listOf("uttaranchal"),
    "India" to listOf("bharat"),
    "United States" to listOf("usa", "america"),
    "United Kingdom" to listOf("uk", "britain"),
    "United Arab Emirates" to listOf("uae"),
    "The Netherlands" to listOf("holland"),
    "Czechia" to listOf("czech republic"),
).mapKeys { keyOf(it.key) }

/**
 * Places at every level (locality, city, district, state, country), by name, a common other name ("bangalore") or
 * GeoNames' other names for it ([aliases], by name). A name shared by several places or levels finds them all.
 */
fun placeSource(names: Collection<String>, aliases: Map<String, List<String>>, idsAt: (String) -> Set<Long>) = NamedSource(
    MatchKind.Place,
    names.filter { it.isNotEmpty() }.distinct().map { name ->
        val key = keyOf(name)
        Named(name, listOf(key) + keysOf(placeAliases[key]) + keysOf(aliases[name])) { idsAt(name) }
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

/**
 * Text as the text index (SQLite's unicode61 tokenizer) splits it: at anything but a letter or digit, in lower case, with
 * accents dropped from Latin letters but ø, æ and ß kept. So "E-ticket" is "e" and "ticket", "15/08/2024" three numbers
 * and "Tromsø" stays "tromsø": a query must split the same way to meet the words read in photos.
 */
fun ftsTokens(text: String): List<String> {
    fun isMark(c: Char) = Character.getType(c) == Character.NON_SPACING_MARK.toInt()
    val tokens = ArrayList<String>()
    val token = StringBuilder()
    var latin = false // the last letter was Latin, so an accent after it goes
    for (c in text.lowercase()) {
        when {
            isMark(c) -> if (!latin) token.append(c) // other scripts keep their marks, as the index does
            !c.isLetterOrDigit() -> {
                if (token.isNotEmpty()) tokens += token.toString()
                token.clear()
                latin = false
            }
            else -> {
                latin = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN
                if (latin) Normalizer.normalize(c.toString(), Normalizer.Form.NFD).forEach { if (!isMark(it)) token.append(it) } else token.append(c)
            }
        }
    }
    if (token.isNotEmpty()) tokens += token.toString()
    return tokens
}

/**
 * Words read in photos (screenshots, receipts, signs) starting with the word typed, so "invo" already finds "invoice".
 * [idsWithText] gets the word as the text index splits it ([ftsTokens]): those parts in a row, the last one a beginning.
 */
fun textSource(idsWithText: (tokens: List<String>) -> Set<Long>) = SearchSource { words ->
    val word = words.singleOrNull() ?: return@SearchSource null
    val tokens = ftsTokens(word.raw).takeIf { it.sumOf(String::length) >= 2 } ?: return@SearchSource null
    val ids = idsWithText(tokens)
    if (ids.isEmpty()) null else Match("“${word.raw}”", MatchKind.Text) { it.id in ids }
}

/** Quoted text: photos with exactly these words in a row ([idsWithPhrase] gets them split as [ftsTokens] does). Always a reading, maybe of nothing. */
fun exactTextSource(idsWithPhrase: (tokens: List<String>) -> Set<Long>) = SearchSource { words ->
    val ids = idsWithPhrase(words.flatMap { ftsTokens(it.raw) })
    Match("“${words.joinToString(" ") { it.raw }}”", MatchKind.Text) { it.id in ids }
}

/**
 * Dates: "today", "yesterday", "this week" or "last month" (whole calendar weeks starting on [weekStart], months, years),
 * a year, a month of any year ("march", "sept") or of one year ("march 2024", "2024 mar"), and a day of a month, every
 * year or one ("15 august", "aug 15", "15th aug 2024").
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
            2 -> relative(w[0], w[1]) ?: monthOfYear(w[0], w[1]) ?: monthOfYear(w[1], w[0]) ?: dayOfMonth(w[0], w[1], null) ?: dayOfMonth(w[1], w[0], null)
            3 -> dayOfMonth(w[0], w[1], w[2]) ?: dayOfMonth(w[1], w[0], w[2])
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

    private fun dayOfMonth(day: String, month: String, year: String?): Match? {
        val d = dayRegex.matchEntire(day)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..31 } ?: return null
        val m = monthOf(month) ?: return null
        if (year == null) {
            if (d > m.maxLength()) return null
            return Match("$d ${titleOf(m)}", MatchKind.Date) { LocalDate.ofEpochDay(it.day).let { date -> date.month == m && date.dayOfMonth == d } }
        }
        val date = runCatching { LocalDate.of(yearOf(year) ?: return null, m, d) }.getOrNull() ?: return null // 30 February
        return days("$d ${titleOf(m)} ${date.year}", date, date)
    }

    private fun days(label: String, from: LocalDate, to: LocalDate): Match {
        val range = from.toEpochDay()..to.toEpochDay()
        return Match(label, MatchKind.Date) { it.day in range }
    }

    private fun yearOf(word: String) = word.takeIf { it.length == 4 }?.toIntOrNull()?.takeIf { it in 1900..2099 }

    // Three letters or more of a month's name: "mar", "sept", "december"
    private fun monthOf(word: String) = if (word.length < 3) null else Month.entries.firstOrNull { it.name.lowercase().startsWith(word) }

    private fun titleOf(month: Month) = month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    private companion object {
        val dayRegex = Regex("(\\d{1,2})(?:st|nd|rd|th)?")
    }
}
