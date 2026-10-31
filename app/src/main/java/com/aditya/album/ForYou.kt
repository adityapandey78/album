package com.aditya.album

import android.content.Context
import android.content.SharedPreferences
import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// The For you tab: memories made on this phone from the Library (on this day, this week last year, trips, month and year
// recaps), then suggestions: Clean up, photos to protect, and Storage. Worked out from the photo list and the smart
// index's towns and text; nothing is uploaded.

/** A memory card: a line in meta above its title, and its photos oldest first, the order it plays in. */
class Memory<T>(val eyebrow: String, val title: String, val items: List<T>)

// A memory plays as a story; this many photos keep it under a minute or so
internal const val STORY_LENGTH = 20

/** Up to [n] of [items] picked evenly across them, in order, so a memory plays its whole span and not only its start. */
fun <T> spread(items: List<T>, n: Int): List<T> = if (items.size <= n) items else List(n) { items[it * items.size / n] }

/** Where most of [items] were taken, as trips name it ([tripNames]), or null when none has a place. */
private fun <T> mainPlace(items: List<T>, placeOf: (T) -> Place?): String? = tripNames(items.mapNotNull(placeOf)).firstOrNull()

/**
 * Photos taken on [today]'s month and day in the latest earlier year that has any, titled with where they were taken
 * ([placeOf]) or how long ago. [photos] are newest first, as the Library holds them; [dayOf] gives a photo's local epoch day.
 */
fun <T> onThisDay(photos: List<T>, today: LocalDate, dayOf: (T) -> Long, placeOf: (T) -> Place? = { null }): Memory<T>? {
    val date = MonthDay.from(today)
    // The same date in every earlier year; 29 February only exists in leap years
    val sameDate = (1900 until today.year).filter(date::isValidYear).map { date.atYear(it).toEpochDay() }.toHashSet()
    val latest = photos.firstOrNull { dayOf(it) in sameDate }?.let(dayOf) ?: return null
    val year = LocalDate.ofEpochDay(latest).year
    val ago = today.year - year
    val items = photos.filter { dayOf(it) == latest }.asReversed()
    return Memory("On this day · $year", mainPlace(items, placeOf) ?: if (ago == 1) "1 year ago" else "$ago years ago", items)
}

/**
 * Photos from the week around [today]'s date last year (three days either side), [photos] newest first. That date itself
 * is left out: when it has photos they are already [onThisDay]'s.
 */
fun <T> weekLastYear(photos: List<T>, today: LocalDate, dayOf: (T) -> Long): Memory<T>? {
    val center = today.minusYears(1)
    val week = center.minusDays(3).toEpochDay()..center.plusDays(3).toEpochDay()
    val items = photos.filter { dayOf(it) in week && dayOf(it) != center.toEpochDay() }.asReversed()
    if (items.isEmpty()) return null
    return Memory("This week · ${center.year}", dayRange(dayOf(items.first()), dayOf(items.last())), items)
}

/**
 * The most recent month before [today]'s with photos, as "September recap" titled with where most were taken
 * ([placeOf]). [photos] newest first.
 */
fun <T> monthRecap(photos: List<T>, today: LocalDate, dayOf: (T) -> Long, placeOf: (T) -> Place? = { null }): Memory<T>? {
    val thisMonth = today.withDayOfMonth(1).toEpochDay()
    val latest = photos.firstOrNull { dayOf(it) < thisMonth }?.let(dayOf) ?: return null
    val month = YearMonth.from(LocalDate.ofEpochDay(latest))
    val days = month.atDay(1).toEpochDay()..month.atEndOfMonth().toEpochDay()
    val items = photos.filter { dayOf(it) in days }.asReversed()
    return Memory("${month.format(monthName)} recap", mainPlace(items, placeOf) ?: "A month in photos", items)
}

/**
 * The year as a recap, "Your 2025": last year in January, this year so far from July. Null in between, and for a year
 * without photos. [photos] newest first.
 */
fun <T> yearRecap(photos: List<T>, today: LocalDate, dayOf: (T) -> Long): Memory<T>? {
    val year = when {
        today.monthValue == 1 -> today.year - 1
        today.monthValue >= 7 -> today.year
        else -> return null
    }
    val days = LocalDate.of(year, 1, 1).toEpochDay()..LocalDate.of(year, 12, 31).toEpochDay()
    val items = photos.filter { dayOf(it) in days }.asReversed()
    if (items.isEmpty()) return null
    return Memory("Your $year", "A year in photos", items)
}

// After the hero, a few memories at most: the section is a calm look back, not the whole archive
private const val MORE_MEMORIES = 4

/**
 * What Memories shows, the hero first: on this day, else the newest trip, else the latest recap. Then, at most
 * [MORE_MEMORIES] of the others in a set order: the newest trip, the month recap, this week, the year recap, older trips.
 */
fun <T> memoriesOf(onThisDay: Memory<T>?, trips: List<Memory<T>>, month: Memory<T>?, week: Memory<T>?, year: Memory<T>?): List<Memory<T>> {
    // This week never leads: its photos are from before this month, so whenever it's there the month recap is too
    val hero = onThisDay ?: trips.firstOrNull() ?: month ?: year ?: return emptyList()
    val others = listOfNotNull(trips.firstOrNull(), month, week, year) + trips.drop(1)
    return listOf(hero) + others.filter { it !== hero }.take(MORE_MEMORIES)
}

// Words that find a candidate in the text index, at least one per kind of card; looksLikeIdCard then reads the whole text.
// Prefixes: "aadha" is Aadhaar and Aadhar, "thru" is "valid thru".
private val ID_WORDS = listOf(
    "aadha", "uidai", "government", "income", "permanent", "passport", "election", "driving",
    "visa", "mastercard", "rupay", "maestro", "amex", "american", "diners", "thru",
)

private val spaces = Regex("""\s+""")
// 1234 5678 9012, not part of a longer run of groups (a 16-digit card or VID). Masked numbers (xxxx xxxx 9012) are left
// out: they mostly come in SMS screenshots, not on the card.
private val aadhaarNumber = Regex("""(?<!\d )\b\d{4} \d{4} \d{4}\b(?! \d)""")
private val panNumber = Regex("""\b[a-z]{5}\d{4}[a-z]\b""") // ABCDE1234F
private val passportCode = Regex("""\bp<[a-z]{3}[a-z<]*<<""") // the machine-readable line: P<INDSHARMA<<RAHUL
private val epicNumber = Regex("""\b[a-z]{3}\d{7}\b""") // voter ID: ABC1234567
private val dlNumber = Regex("""\b[a-z]{2}[- ]?\d{2}[- ]?\d{4} ?\d{7}\b""") // MH12 20110012345
private val cardNumber = Regex("""(?<!\d )\b(\d{4} \d{4} \d{4} \d{4}|3[47]\d{2} \d{6} \d{5})\b(?! \d)""") // 4 x 4, or Amex 4-6-5
private val cardNetwork = Regex("""\b(visa|mastercard|rupay|maestro|amex|american express|diners club)\b""")

/**
 * Whether text read from a photo is an ID or bank card: Aadhaar, PAN, passport, voter ID, driving licence, or a card with
 * its full number. Strict on purpose: a phrase alone (a news screenshot about Aadhaar, a form asking for a PAN) isn't
 * enough, it also needs the card's number or a second phrase only the card carries.
 */
fun looksLikeIdCard(text: String): Boolean {
    val t = text.lowercase().replace(spaces, " ")
    fun has(vararg phrases: String) = phrases.any { it in t }
    return (has("aadhaar", "aadhar", "uidai", "unique identification authority", "government of india") && aadhaarNumber.containsMatchIn(t)) ||
        (has("income tax department", "permanent account number") && panNumber.containsMatchIn(t)) ||
        passportCode.containsMatchIn(t) || (has("passport") && has("republic of india")) ||
        (has("election commission") && (has("identity card", "elector") || epicNumber.containsMatchIn(t))) ||
        (has("driving licence", "driving license") && (has("union of india", "indian union") || dlNumber.containsMatchIn(t))) ||
        ((cardNetwork.containsMatchIn(t) || has("valid thru", "valid from", "good thru")) &&
            cardNumber.findAll(t).any { luhn(it.value.filter(Char::isDigit)) })
}

/** The checksum every bank card number passes, which a random run of digits mostly fails. */
private fun luhn(digits: String) = digits.reversed().withIndex().sumOf { (i, c) ->
    val d = c.digitToInt()
    if (i % 2 == 0) d else if (d > 4) d * 2 - 9 else d * 2
} % 10 == 0

/** Ids of indexed photos whose text looks like an ID card. Blocking: reads the smart index. */
private fun idCardIds(): Set<Long> =
    ID_WORDS.flatMapTo(HashSet()) { SmartIndex.idsWithText(it) }.filterTo(HashSet()) { SmartIndex.textOf(it)?.let(::looksLikeIdCard) == true }

/**
 * Ids of indexed photos that look like ID cards, null until first read. Text is read as the index runs, so they're read
 * again whenever a pass ends (and once at the start, even mid-pass, so a page opened then isn't left empty).
 */
@Composable
fun rememberIdCardIds(): Set<Long>? {
    val indexing = SmartIndex.progress.collectAsState().value.let { it.done < it.total }
    return produceState<Set<Long>?>(null, indexing) {
        if (indexing && value != null) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { idCardIds() }.onFailure { CrashLog.note("id cards: $it") }.getOrDefault(emptySet()) }
    }.value
}

/**
 * The photos For you suggests protecting: those of [photos] (in their order) the index [found] as ID cards, less the ones
 * the user marked Not a document ([dismissed]).
 */
fun <T> idSuggestions(photos: List<T>, found: Set<Long>, dismissed: Set<Long>, id: (T) -> Long): List<T> =
    if (found.isEmpty()) emptyList() else photos.filter { id(it) in found && id(it) !in dismissed }

/** Photos the user marked Not a document, saved in the "settings" preferences: For you stops suggesting them. */
object NotDocuments {
    private const val KEY = "not_documents"
    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow(emptySet<Long>())
    val ids = state.asStateFlow()

    /** Reads the saved set once. Safe to call repeatedly. */
    fun load(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE).also {
            state.value = it.getStringSet(KEY, null).orEmpty().mapNotNullTo(HashSet()) { id -> id.toLongOrNull() }
        }
    }

    /** Marks [ids] as not documents, or ([on] false) as suggestible again, for Undo. */
    fun mark(ids: Set<Long>, on: Boolean = true) {
        // ponytail: ids of photos deleted since stay in the set, a few bytes each; prune against the Library if it ever grows
        state.update { if (on) it + ids else it - ids }
        prefs?.edit { putStringSet(KEY, state.value.mapTo(HashSet()) { it.toString() }) }
    }
}

private val monthName = DateTimeFormatter.ofPattern("MMMM")
private val dayMonth = DateTimeFormatter.ofPattern("d MMM")

/** "24 Oct", "20 to 26 Oct" or "29 Oct to 2 Nov", the way the rest of Album writes dates. */
fun dayRange(first: Long, last: Long): String {
    val a = LocalDate.ofEpochDay(first)
    val b = LocalDate.ofEpochDay(last)
    return when {
        a == b -> a.format(dayMonth)
        a.month == b.month -> "${a.dayOfMonth} to ${b.format(dayMonth)}"
        else -> "${a.format(dayMonth)} to ${b.format(dayMonth)}"
    }
}

/** The parts of the storage bar, in its order. WhatsApp and screenshots are carved out of photos and videos. [app] lends its icon. */
enum class StorageKind(val label: String, val app: MediaApp? = null) {
    Photos("Photos"), Videos("Videos"), WhatsApp("WhatsApp", MediaApp.WhatsApp), Screenshots("Screenshots"),
}

/** Which part of the storage bar a file counts towards. [path] is its folder relative to the storage root. */
fun storageKind(isVideo: Boolean, path: String, bucket: String, name: String) = when {
    // WhatsApp keeps its media in "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images" and the like
    path.contains("whatsapp", ignoreCase = true) || bucket.contains("whatsapp", ignoreCase = true) -> StorageKind.WhatsApp
    isScreenshot(path, name) -> StorageKind.Screenshots
    isVideo -> StorageKind.Videos
    else -> StorageKind.Photos
}

/** Bytes per part of the storage bar, in the bar's order, leaving out parts with nothing in them. */
fun <T> storageOf(items: List<T>, kind: (T) -> StorageKind, size: (T) -> Long): List<Pair<StorageKind, Long>> {
    val bytes = LongArray(StorageKind.entries.size)
    items.forEach { bytes[kind(it).ordinal] += size(it) }
    return StorageKind.entries.map { it to bytes[it.ordinal] }.filter { it.second > 0 }
}

/** A clean-up pass with at least this many photos to go says so, instead of showing counts that move while it runs. */
const val STILL_CHECKING_MIN = 100

/**
 * How many photos Clean up is still checking, or null when its counts can show: from the start of a pass with at least
 * [STILL_CHECKING_MIN] to go until it ends, so they never move while you read them. A few new photos are done in a moment.
 * [shown] is the last answer; [scan] is the clean-up pass's progress, null when none runs.
 */
fun stillChecking(shown: Int?, scan: IndexProgress?): Int? {
    val left = scan?.let { it.total - it.done } ?: return null
    return left.takeIf { shown != null || it >= STILL_CHECKING_MIN }
}

/** Memories in [media] (newest first) on [today], the hero first. See [memoriesOf]. */
private fun memoriesIn(media: List<Media>, today: LocalDate, places: List<Group<Place, Media>>?): List<Memory<Media>> {
    // Memories play as photo stories; videos would need their own length
    val photos = media.filter { !it.isVideo }
    val placeOf = placesById(places.orEmpty())
    val place = { m: Media -> placeOf[m.id] }
    return memoriesOf(
        onThisDay(photos, today, Media::day, place), trips(photos, Media::day, place), monthRecap(photos, today, Media::day, place),
        weekLastYear(photos, today, Media::day), yearRecap(photos, today, Media::day),
    )
}

/**
 * A memory's few words under its story ring: "On this day", "September", "2025", or a trip's place. Rings are narrow,
 * so the eyebrow's detail after the dot is left out.
 */
fun ringCaption(eyebrow: String, title: String): String = when {
    eyebrow.startsWith("Trip") -> title
    eyebrow.endsWith(" recap") -> eyebrow.removeSuffix(" recap")
    eyebrow.startsWith("Your ") -> eyebrow.removePrefix("Your ")
    else -> eyebrow.substringBefore(" · ")
}

/** Which memory this is, for the played set: its eyebrow and first photo, so next year's same-named recap is new again. */
private fun keyOf(m: Memory<Media>) = "${m.eyebrow}|${m.items.first().id}"

/**
 * The played set after playing [key]. Only keys of memories still shown ([shown]) are kept, so the set stays a handful
 * and a memory that leaves For you is simply new if it ever comes back.
 */
fun markPlayed(played: Set<String>, key: String, shown: Collection<String>): Set<String> = played.filterTo(HashSet()) { it in shown } + key

/** Memories played as a story, saved in the "settings" preferences; Stories gives the others a flash ring as new. */
object PlayedMemories {
    private const val KEY = "memories_played"
    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow(emptySet<String>())
    val keys = state.asStateFlow()

    /** Reads the saved set once. Safe to call repeatedly. */
    fun load(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE).also {
            state.value = it.getStringSet(KEY, null).orEmpty().toSet()
        }
    }

    fun mark(key: String, shown: Collection<String>) {
        state.update { markPlayed(it, key, shown) }
        prefs?.edit { putStringSet(KEY, state.value) }
    }
}

// The clean-up pass's name in IndexStatus, as Cleanup reports it
private const val CLEANUP_JOB = "Clean-up"

// Card sizes from the design: the hero gets more of the screen than the memories beside each other under it
private val BigMemoryHeight = 300.dp
private val MemoryWidth = 148.dp
private val MemoryHeight = 196.dp
private val RingWidth = 72.dp

/**
 * The For you tab: its title (More holds Settings), then the memories, then Suggestions. Calm shows the hero memory and
 * the others in a strip under it; Stories shows every memory as a ring along the top (a flash ring until it's played)
 * and the hero under them. Suggestions: Clean up, the photos that look like ID cards (only when there are some) and Storage.
 * [contentPadding] already clears the status bar and the floating bottom bar. [onPlay] opens a memory as a story with its
 * title, eyebrow and up to [STORY_LENGTH] photos; every memory card plays through `play` here. [cleanupCount] is how many
 * photos Clean up suggests; [onCleanup] opens it on them, and [onProtect] opens the review of the photos that look like
 * ID cards ([ProtectReviewScreen]).
 */
@Composable
fun ForYouScreen(
    photos: List<Media>,
    state: LazyListState,
    contentPadding: PaddingValues,
    cleanupCount: Int,
    onPlay: (title: String, eyebrow: String, items: List<Media>) -> Unit,
    onCleanup: () -> Unit,
    onStorage: () -> Unit,
    onProtect: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    // The app can stay open overnight; "on this day" moves on when it comes back
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleResumeEffect(Unit) {
        today = LocalDate.now()
        onPauseOrDispose {}
    }
    // Towns per photo, shared with Collections and re-read at most every few seconds while the index runs
    val places by Explore.places.collectAsState()
    // Passes over the whole Library (20k photos on a big phone) stay off the main thread, so switching tabs never stutters
    val memories by produceState<List<Memory<Media>>?>(null, photos, today, places) {
        value = withContext(Dispatchers.Default) { memoriesIn(photos, today, places) }
    }
    // Storage counts every item, as the storage screen it opens does: WhatsApp may live in folders hidden from the Library
    val all = Gallery.media.collectAsState().value
    val used by produceState<Long?>(null, all) {
        if (all == null) return@produceState // not loaded yet
        value = withContext(Dispatchers.Default) { all.sumOf { it.size } }
    }
    // Where shared storage lives, which is the app's own partition (see freeSpace)
    val free by produceState<Long?>(null, all) { value = withContext(Dispatchers.IO) { freeSpace(context, PHONE_STORAGE) } }
    // Only photos still in the Library count: ones moved to ID cards (or the Private safe) leave MediaStore, so they drop
    // out at once, before the index forgets them
    val idCardIds = rememberIdCardIds()
    NotDocuments.load(context)
    val notDocuments by NotDocuments.ids.collectAsState()
    val idCards = remember(photos, idCardIds, notDocuments) { idSuggestions(photos, idCardIds.orEmpty(), notDocuments, Media::id) }
    val summary by Cleanup.summary.collectAsState()
    val left = produceState<Int?>(null) { IndexStatus.running.collect { value = stillChecking(value, it[CLEANUP_JOB]) } }.value
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes) // KB, MB or GB as fits, in the phone's locale
    PlayedMemories.load(context)
    val played by PlayedMemories.keys.collectAsState()
    val stories = AlbumTheme.spec.appearance.forYouStyle == ForYouStyle.Stories
    val play = { m: Memory<Media> ->
        PlayedMemories.mark(keyOf(m), memories.orEmpty().map(::keyOf))
        onPlay(m.title, m.eyebrow, spread(m.items, STORY_LENGTH))
    }
    var more by rememberSaveable { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), state, contentPadding) {
        item(key = "title") {
            LargeTopBar("For you", subtitle = "Made on this phone, from your photos") { AlbumIconButton(Sym.MoreVert, "More", { more = true }) }
        }
        // Null for a moment while they're worked out; an empty state then would only flash
        memories?.let { list ->
            if (stories && list.isNotEmpty()) item(key = "rings") { MemoryRings(list, played, play) }
            item(key = "hero") {
                val hero = list.firstOrNull()
                if (hero == null) EmptyState(Sym.Favorite, "No memories yet", "Photos from this day in earlier years, trips and recaps show up here.")
                else MemoryCard(hero, big = true, onClick = { play(hero) }, Modifier.padding(horizontal = Space.s4).fillMaxWidth().heightIn(min = BigMemoryHeight))
            }
            if (!stories && list.size > 1) {
                item(key = "memories head") { SectionHead("Memories") }
                item(key = "memories") { MemoryStrip(list.drop(1), play) }
            }
        }
        item(key = "suggestions") {
            Group(Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s6), title = "Suggestions") {
                GroupRow(
                    "Clean up", onCleanup, icon = Sym.CleaningServices,
                    sub = when {
                        left != null -> "Still checking ${counted(left, "photo", "photos")}"
                        cleanupCount > 0 -> "${size(summary?.reviewBytes ?: 0)} you can free · ${itemCount(cleanupCount)}"
                        summary != null -> "Nothing to clean up right now"
                        else -> null // not looked at yet: a moment after the Library loads
                    },
                )
                if (idCards.isNotEmpty()) {
                    GroupRow(
                        "Protect ${counted(idCards.size, "ID photo", "ID photos")}", onProtect, icon = Sym.IdCard,
                        sub = "Aadhaar, PAN or bank cards, look them over and lock them away",
                    )
                }
                GroupRow(
                    "Storage", onStorage, icon = Sym.Storage,
                    sub = used?.let { u -> "${size(u)} used" + (free?.let { " · ${size(it)} free" } ?: "") },
                )
            }
        }
    }
    if (more) {
        Sheet({ more = false }, "For you") {
            MenuRow(Sym.Settings, "Settings", {
                more = false
                onSettings()
            }, sub = SETTINGS_SUB)
        }
    }
}

/** The memories after the hero, side by side in a strip that scrolls sideways and settles on a card's start. */
@Composable
private fun MemoryStrip(memories: List<Memory<Media>>, onPlay: (Memory<Media>) -> Unit) {
    val strip = rememberLazyListState()
    LazyRow(
        state = strip,
        contentPadding = PaddingValues(horizontal = Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        flingBehavior = rememberSnapFlingBehavior(strip, SnapPosition.Start),
    ) {
        items(memories) { m -> MemoryCard(m, big = false, onClick = { onPlay(m) }, Modifier.width(MemoryWidth).height(MemoryHeight)) }
    }
}

/**
 * Stories' row of memories: each a round cover with a short caption, scrolling sideways. One not yet played ([played]
 * keys) has a flash-ink ring, the rest a quiet line-strong one. Tapping plays it.
 */
@Composable
private fun MemoryRings(memories: List<Memory<Media>>, played: Set<String>, onPlay: (Memory<Media>) -> Unit) {
    val c = AlbumTheme.colors
    LazyRow(
        contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = Space.s2, bottom = Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        items(memories, key = ::keyOf) { m ->
            val new = keyOf(m) !in played
            val source = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .width(RingWidth)
                    .opensScreen()
                    .clickable(source, indication = null, role = Role.Button, onClickLabel = "Play") { onPlay(m) }
                    .semantics(mergeDescendants = true) { contentDescription = m.title + if (new) ", new" else "" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                // The 2dp ring, then a 2dp gap of canvas, around the 64dp photo
                Box(
                    Modifier
                        .pressShrink(source, 0.92f)
                        .size(RingWidth)
                        .border(2.dp, if (new) c.flashInk else c.lineStrong, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape),
                ) { Thumbnail(m.items.first(), Modifier.matchParentSize()) }
                Text(
                    ringCaption(m.eyebrow, m.title), style = AlbumTheme.type.caption, color = c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A memory: its first photo as the cover, faded to scrim-photo under the eyebrow and title, shrinking a little while
 * pressed. The hero is [big], with its title in display; the strip's cards are smaller, with tighter corners. Tapping
 * plays it.
 */
@Composable
private fun MemoryCard(memory: Memory<Media>, big: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .opensScreen() // the memory grows out of this card
            .pressShrink(source, 0.97f)
            .clip(RoundedCornerShape(if (big) Space.radiusLg else Space.radiusMd))
            .clickable(source, indication = null, role = Role.Button, onClickLabel = "Play", onClick = onClick),
        contentAlignment = Alignment.BottomStart,
    ) {
        Thumbnail(memory.items.first(), Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to c.scrimPhoto)))
        Column(Modifier.padding(if (big) Space.s4 else Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Text(memory.eyebrow, style = type.meta, color = c.onPhoto)
            Text(memory.title, style = if (big) type.display else type.titleMd, color = c.onPhoto)
        }
    }
}

/**
 * The design's small Album mark, as a memory's story shows it top left: three rounded tiles in ink and a flash disc in
 * the fourth corner, on a 48-unit grid. Draw it inside a dark AlbumTheme over a photo.
 */
@Composable
fun LogoGlyph(modifier: Modifier = Modifier, size: Dp = Space.iconSm) {
    val c = AlbumTheme.colors
    Canvas(modifier.size(size).semantics { contentDescription = "Album" }) {
        val u = this.size.width / 48f
        val tile = Size(20 * u, 20 * u)
        listOf(2f to 2f, 2f to 26f, 26f to 26f).forEach { (x, y) -> drawRoundRect(c.ink, Offset(x * u, y * u), tile, CornerRadius(5 * u)) }
        drawCircle(c.flash, 10 * u, Offset(36 * u, 12 * u))
    }
}
