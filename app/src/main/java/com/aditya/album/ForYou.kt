package com.aditya.album

import android.content.Context
import android.text.format.Formatter
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// The For you tab, in two sections. Memories: made on this phone from the Library (on this day, this week last year,
// trips, month and year recaps), and photos to protect. Clean up: where the storage goes and what can go. Worked out from
// the photo list and the smart index's towns and text; nothing is uploaded.

/** A memory card: a line in meta above its title, and its photos oldest first, the order it plays in. */
class Memory<T>(val eyebrow: String, val title: String, val items: List<T>)

// A memory plays as a story; this many photos keep it under a minute or so
private const val STORY_LENGTH = 20

/** Up to [n] of [items] picked evenly across them, in order, so a memory plays its whole span and not only its start. */
fun <T> spread(items: List<T>, n: Int): List<T> = if (items.size <= n) items else List(n) { items[it * items.size / n] }

/**
 * Photos taken on [today]'s month and day in the latest earlier year that has any. [photos] are newest first, as the
 * Library holds them; [dayOf] gives a photo's local epoch day.
 */
fun <T> onThisDay(photos: List<T>, today: LocalDate, dayOf: (T) -> Long): Memory<T>? {
    val date = MonthDay.from(today)
    // The same date in every earlier year; 29 February only exists in leap years
    val sameDate = (1900 until today.year).filter(date::isValidYear).map { date.atYear(it).toEpochDay() }.toHashSet()
    val latest = photos.firstOrNull { dayOf(it) in sameDate }?.let(dayOf) ?: return null
    val year = LocalDate.ofEpochDay(latest).year
    val ago = today.year - year
    return Memory("On this day · $year", if (ago == 1) "1 year ago" else "$ago years ago", photos.filter { dayOf(it) == latest }.asReversed())
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

/** The most recent month before [today]'s with photos, as a recap titled with the month's name. [photos] newest first. */
fun <T> monthRecap(photos: List<T>, today: LocalDate, dayOf: (T) -> Long): Memory<T>? {
    val thisMonth = today.withDayOfMonth(1).toEpochDay()
    val latest = photos.firstOrNull { dayOf(it) < thisMonth }?.let(dayOf) ?: return null
    val month = YearMonth.from(LocalDate.ofEpochDay(latest))
    val days = month.atDay(1).toEpochDay()..month.atEndOfMonth().toEpochDay()
    return Memory("Recap · ${month.year}", month.format(monthName), photos.filter { dayOf(it) in days }.asReversed())
}

/**
 * The year as a recap titled with the year: last year in January, this year so far from July. Null in between, and for a
 * year without photos. [photos] newest first.
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
    return Memory(if (year == today.year) "So far this year" else "Year recap", "$year", items)
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

/** For you's two sections, switched by the chips under its title. */
enum class ForYouSection(val label: String) { Memories("Memories"), Cleanup("Clean up") }

/** The section For you opens on: the one picked last time ([saved], as the "settings" prefs keep it), else Memories. */
fun forYouSection(saved: String?) = ForYouSection.entries.find { it.name == saved } ?: ForYouSection.Memories

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
    return memoriesOf(
        onThisDay(photos, today, Media::day), trips(photos, Media::day) { placeOf[it.id] }, monthRecap(photos, today, Media::day),
        weekLastYear(photos, today, Media::day), yearRecap(photos, today, Media::day),
    )
}

// The clean-up pass's name in IndexStatus, as Cleanup reports it
private const val CLEANUP_JOB = "Clean-up"

// The "settings" prefs key that remembers the section For you was left on
private const val SECTION_KEY = "for_you_section"

// Card heights from the design: the hero gets more of the screen than the memories under it
private val BigMemoryHeight = 300.dp
private val MemoryHeight = 190.dp
private val RowHeight = 56.dp // as tall as a menu row
private val Hairline = 1.dp

/**
 * The For you tab: its title, a chip per section, and the section picked, which For you opens on next time too. Memories
 * is a hero memory, a few more under it and, quietly, photos that look like ID cards; Clean up is where the storage goes
 * and a row per clean-up category. [contentPadding] already clears the status bar and the floating bottom bar. [onPlay]
 * opens a memory as a story with its title, eyebrow and up to [STORY_LENGTH] photos. [cleanupCount] is how many photos
 * Clean up suggests; [onCleanup] opens it on all of them and [onCleanupCategory] on one category (a [CleanupKind] name);
 * [onProtect] gets the photos that look like ID cards, for the Private safe.
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
    onProtect: (List<Media>) -> Unit,
    onCleanupCategory: (String) -> Unit,
) {
    val prefs = LocalContext.current.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var section by rememberSaveable { mutableStateOf(forYouSection(prefs.getString(SECTION_KEY, null))) }
    fun pick(s: ForYouSection) {
        section = s
        prefs.edit { putString(SECTION_KEY, s.name) }
    }
    // The app can stay open overnight; "on this day" moves on when it comes back
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleResumeEffect(Unit) {
        today = LocalDate.now()
        onPauseOrDispose {}
    }
    // Towns per photo, shared with Collections and re-read at most every few seconds while the index runs
    val places by Explore.places.collectAsState()
    // Passes over the whole Library (20k photos on a big phone) stay off the main thread, so switching tabs never stutters.
    // Both sections are worked out here, so switching between them shows each at once.
    val memories by produceState<List<Memory<Media>>?>(null, photos, today, places) {
        value = withContext(Dispatchers.Default) { memoriesIn(photos, today, places) }
    }
    // Storage counts every item, as the storage screen it opens does: WhatsApp may live in folders hidden from the Library
    val all = Gallery.media.collectAsState().value
    val storage by produceState<List<Pair<StorageKind, Long>>?>(null, all) {
        if (all == null) return@produceState // not loaded yet
        value = withContext(Dispatchers.Default) { storageOf(all, { storageKind(it.isVideo, it.path, it.bucketName, it.name) }, Media::size) }
    }
    // Text is read as the index runs, so ID cards are looked for again whenever a pass ends. Only photos still in the
    // Library count: ones just moved to the Private safe drop out at once, before the index forgets them.
    val indexing = SmartIndex.progress.collectAsState().value.let { it.done < it.total }
    val idCardIds by produceState(emptySet<Long>(), indexing) {
        if (indexing) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { idCardIds() }.onFailure { CrashLog.note("id cards: $it") }.getOrDefault(emptySet()) }
    }
    val idCards = remember(photos, idCardIds) { if (idCardIds.isEmpty()) emptyList() else photos.filter { it.id in idCardIds } }
    val still = AlbumTheme.spec.still
    LazyColumn(Modifier.fillMaxSize(), state, contentPadding) {
        item(key = "title") { LargeTopBar("For you", subtitle = "Made on this phone, from your photos") }
        item(key = "sections") {
            // Wraps rather than clips when the phone's font size is large
            FlowRow(
                Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s4),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                ForYouSection.entries.forEach { s ->
                    val on = s == section
                    // A button that says it's selected: tapping the picked one again changes nothing, so it isn't a toggle
                    Chip(s.label, { pick(s) }, Modifier.semantics { selected = on }, selected = on, toggle = false)
                }
            }
        }
        // A section is a handful of cards, so it's one item, which lets the two crossfade
        item(key = "section") {
            Crossfade(section, animationSpec = if (still) snap() else tween(220), label = "section") { s ->
                when (s) {
                    ForYouSection.Memories -> MemoriesSection(memories, idCards, onPlay, onProtect)
                    ForYouSection.Cleanup -> CleanupSection(storage, cleanupCount, onCleanup, onStorage, onCleanupCategory)
                }
            }
        }
    }
}

/**
 * The hero memory, the others under it all the same size, then the ID card guard: only when there are photos that look
 * like ID cards, and quiet. Every card plays or opens when tapped. [memories] is null while they're worked out.
 */
@Composable
private fun MemoriesSection(
    memories: List<Memory<Media>>?,
    idCards: List<Media>,
    onPlay: (title: String, eyebrow: String, items: List<Media>) -> Unit,
    onProtect: (List<Media>) -> Unit,
) {
    if (memories == null) return // a moment; an empty state now would only flash
    Column(Modifier.padding(horizontal = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        if (memories.isEmpty()) {
            EmptyState(Sym.Favorite, "No memories yet", "Photos from this day in earlier years, trips and recaps show up here.")
        }
        memories.forEachIndexed { i, m ->
            MemoryCard(m, big = i == 0, onClick = { onPlay(m.title, m.eyebrow, spread(m.items, STORY_LENGTH)) }, Modifier.fillMaxWidth())
        }
        if (idCards.isNotEmpty()) {
            LinkRow(
                { Icon(Sym.IdCard, null, tint = AlbumTheme.colors.inkMuted) },
                counted(idCards.size, "photo looks like an ID card", "photos look like ID cards"),
                caption = "Keep Aadhaar, PAN and bank cards in the Private safe",
                count = null,
                onClickLabel = "Review and protect",
                onClick = { onProtect(idCards) },
                modifier = Modifier.padding(top = Space.s3).border(Hairline, AlbumTheme.colors.line, RoundedCornerShape(Space.radiusMd)),
            )
        }
    }
}

/**
 * Where the storage goes, then a row per clean-up category with something in it, each opening Clean up on that category;
 * "See all" opens it on every suggestion. While a longer clean-up pass runs its counts would keep moving, so the rows leave
 * them out and a line under the heading says how many photos are left to check instead. [storage] is null while it's
 * worked out.
 */
@Composable
private fun CleanupSection(
    storage: List<Pair<StorageKind, Long>>?,
    toReview: Int,
    onCleanup: () -> Unit,
    onStorage: () -> Unit,
    onCategory: (String) -> Unit,
) {
    if (storage == null) return // a moment; without it the rest would jump down when it comes
    val context = LocalContext.current
    val summary by Cleanup.summary.collectAsState()
    val left = produceState<Int?>(null) { IndexStatus.running.collect { value = stillChecking(value, it[CLEANUP_JOB]) } }.value
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes) // KB, MB or GB as fits, in the phone's locale
    // Straight from the categories and their own labels and icons, so a new or renamed one shows up here as it is
    val kinds = CleanupKind.entries.mapNotNull { kind -> summary?.kinds?.get(kind)?.takeIf { it.count > 0 }?.let { kind to it } }
    val caption = when {
        left != null -> "Still checking ${counted(left, "photo", "photos")}"
        toReview > 0 -> counted(toReview, "photo to review", "photos to review")
        summary != null && kinds.isEmpty() -> "Nothing to clean up right now"
        else -> null // not looked at yet: a moment after the Library loads
    }
    Column {
        if (storage.isNotEmpty()) StorageCard(storage, onStorage, Modifier.padding(horizontal = Space.s4))
        if (caption != null || kinds.isNotEmpty()) {
            SectionHead("Free up space", action = "See all".takeIf { toReview > 0 }, onAction = onCleanup)
        }
        caption?.let { Note(it) }
        kinds.forEach { (kind, n) ->
            LinkRow(
                { KindIcon(kind) }, kind.label,
                // Browse categories aren't suggestions: what they take, not what they'd free
                caption = if (left != null) null else if (kind.suggested) "Frees ${size(n.bytes)}" else "Uses ${size(n.bytes)}",
                count = n.count.takeIf { left == null },
                onClickLabel = "Review",
                onClick = { onCategory(kind.name) },
                modifier = Modifier.padding(horizontal = Space.s1),
            )
        }
    }
}

/** A memory: its first photo as the cover, faded to scrim-photo under the eyebrow and title. Tapping plays it. */
@Composable
private fun MemoryCard(memory: Memory<Media>, big: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    Box(
        modifier
            .heightIn(min = if (big) BigMemoryHeight else MemoryHeight)
            .clip(RoundedCornerShape(Space.radiusLg))
            .clickable(role = Role.Button, onClickLabel = "Play", onClick = onClick),
        contentAlignment = Alignment.BottomStart,
    ) {
        Thumbnail(memory.items.first(), Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to c.scrimPhoto)))
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Text(memory.eyebrow, style = type.meta, color = c.onPhoto)
            Text(memory.title, style = if (big) type.display else type.titleLg, color = c.onPhoto)
        }
    }
}

/**
 * A row that opens something: [icon], [label] over an optional [caption], an optional [count] in meta, and a chevron.
 * [onClickLabel] is what TalkBack says a double tap does. Storage's clean-up rows are these too.
 */
@Composable
fun LinkRow(
    icon: @Composable () -> Unit,
    label: String,
    caption: String?,
    count: Int?,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    Row(
        modifier.fillMaxWidth().heightIn(min = RowHeight).clip(RoundedCornerShape(Space.radiusMd))
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = Space.s3, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(label, style = type.body, color = c.ink)
            caption?.let { Text(it, style = type.caption, color = c.inkMuted) }
        }
        // On screen the label says what's counted; TalkBack hears the unit
        count?.let { Text("%,d".format(it), Modifier.semantics { contentDescription = itemCount(it) }, style = type.meta, color = c.inkMuted) }
        Icon(Sym.ChevronRight, null, tint = c.inkMuted)
    }
}

/** How much space photos and videos take: the total, a bar split by kind, its legend, and a quiet way to the details. */
@Composable
private fun StorageCard(parts: List<Pair<StorageKind, Long>>, onStorage: () -> Unit, modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val context = LocalContext.current
    val shape = RoundedCornerShape(Space.radiusLg)
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes) // KB, MB or GB as fits, in the phone's locale
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.surface).border(Hairline, c.line, shape).padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(size(parts.sumOf { it.second }), style = type.titleLg, color = c.ink)
                Text("Used by photos and videos", style = type.caption, color = c.inkMuted)
            }
            // Its touch area reaches into the card's padding, so its words line up with the bar's end
            AlbumButton("See storage", onStorage, Modifier.offset(x = Space.s3), ButtonVariant.Quiet)
        }
        StorageSplit(parts, ::size)
    }
}
