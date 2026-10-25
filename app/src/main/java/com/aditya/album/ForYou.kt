package com.aditya.album

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// The For you tab: memories made on this phone from the Library (on this day, this week last year, trips, month and year
// recaps), photos to clean up or protect, and where the storage goes. Worked out from the photo list and the smart index's
// towns and text; nothing is uploaded.

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

/** The parts of the storage bar, in its order. WhatsApp and screenshots are carved out of photos and videos. */
enum class StorageKind(val label: String) { Photos("Photos"), Videos("Videos"), WhatsApp("WhatsApp"), Screenshots("Screenshots") }

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

private class ForYou(
    val onThisDay: Memory<Media>?,
    /** The smaller memories, two to a row: the newest trip and the recaps first, then older trips. */
    val memories: List<Memory<Media>>,
    val storage: List<Pair<StorageKind, Long>>,
)

private fun forYouOf(media: List<Media>, today: LocalDate, places: List<Group<Place, Media>>?): ForYou {
    // Memories play as photo stories; videos would need their own length
    val photos = media.filter { !it.isVideo }
    val placeOf = placesById(places.orEmpty())
    val trips = trips(photos, Media::day) { placeOf[it.id] }
    return ForYou(
        onThisDay(photos, today, Media::day),
        listOfNotNull(
            trips.firstOrNull(), monthRecap(photos, today, Media::day), weekLastYear(photos, today, Media::day),
            yearRecap(photos, today, Media::day),
        ) + trips.drop(1),
        storageOf(media, { storageKind(it.isVideo, it.path, it.bucketName, it.name) }, Media::size),
    )
}

// Card heights from the design: the big memory gets more of the screen than the two beside each other
private val BigMemoryHeight = 300.dp
private val MemoryHeight = 190.dp
private val StorageBarHeight = 10.dp
private val LegendDot = 8.dp

/**
 * The For you tab. [contentPadding] already clears the status bar and the floating bottom bar. [onPlay] opens a memory
 * as a story with its title, eyebrow and up to [STORY_LENGTH] photos. [cleanupCount] is how many photos swipe to clean
 * has to review; [onProtect] gets the photos that look like ID cards, for the Private safe.
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
) {
    // The app can stay open overnight; "on this day" moves on when it comes back
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleResumeEffect(Unit) {
        today = LocalDate.now()
        onPauseOrDispose {}
    }
    // Towns per photo, shared with Collections and re-read at most every few seconds while the index runs
    val places by Explore.places.collectAsState()
    // A pass over the whole Library (20k photos on a big phone) stays off the main thread, so switching tabs never stutters
    val model by produceState<ForYou?>(null, photos, today, places) {
        value = withContext(Dispatchers.Default) { forYouOf(photos, today, places) }
    }
    // Text is read as the index runs, so ID cards are looked for again whenever a pass ends. Only photos still in the
    // Library count: ones just moved to the Private safe drop out at once, before the index forgets them.
    val indexing = SmartIndex.progress.collectAsState().value.let { it.done < it.total }
    val idCardIds by produceState(emptySet<Long>(), indexing) {
        if (indexing) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { idCardIds() }.onFailure { CrashLog.note("id cards: $it") }.getOrDefault(emptySet()) }
    }
    val idCards = remember(photos, idCardIds) { if (idCardIds.isEmpty()) emptyList() else photos.filter { it.id in idCardIds } }
    LazyColumn(Modifier.fillMaxSize(), state, contentPadding) {
        item(key = "title") { LargeTopBar("For you", subtitle = "Made on this phone, from your photos") }
        val m = model ?: return@LazyColumn
        fun card(key: String, content: @Composable () -> Unit) = item(key = key) {
            Box(Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s3)) { content() }
        }
        fun play(memory: Memory<Media>) = onPlay(memory.title, memory.eyebrow, spread(memory.items, STORY_LENGTH))
        val rows = m.memories.chunked(2)
        fun row(i: Int) = card("memories $i") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                rows[i].forEach { MemoryCard(it, big = false, onClick = { play(it) }, Modifier.weight(1f)) }
            }
        }

        m.onThisDay?.let { card("on this day") { MemoryCard(it, big = true, onClick = { play(it) }, Modifier.fillMaxWidth()) } }
        if (rows.isNotEmpty()) row(0)
        if (m.onThisDay == null && rows.isEmpty()) {
            card("empty") {
                EmptyState(Sym.Favorite, "No memories yet", "Photos from this day in earlier years, trips and recaps show up here.")
            }
        }
        // What needs doing comes after the first memories, before older trips push it off the screen
        if (cleanupCount > 0) {
            card("cleanup") {
                InfoCard(Sym.CleaningServices, counted(cleanupCount, "photo to review", "photos to review"), "Similar shots, blurry photos and old screenshots") {
                    AlbumButton("Start swipe to clean", onCleanup, Modifier.fillMaxWidth(), ButtonVariant.Primary, Sym.Swipe)
                }
            }
        }
        if (idCards.isNotEmpty()) {
            val title = if (idCards.size == 1) "1 photo looks like an ID card" else "${"%,d".format(idCards.size)} photos look like ID cards"
            card("id cards") {
                InfoCard(Sym.IdCard, title, "Aadhaar, PAN or bank cards found in your Library") {
                    AlbumButton("Review and protect", { onProtect(idCards) }, Modifier.fillMaxWidth(), icon = Sym.Lock)
                }
            }
        }
        for (i in 1 until rows.size) row(i)
        if (m.storage.isNotEmpty()) card("storage") { StorageCard(m.storage, onStorage) }
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

/** A card on surface: an icon in a flash-soft disc beside its title and caption, then [content]. */
@Composable
private fun InfoCard(icon: Sym, title: String, caption: String, content: @Composable () -> Unit) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val shape = RoundedCornerShape(Space.radiusLg)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Box(Modifier.size(Space.target).background(c.flashSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.flashInk)
            }
            Column {
                Text(title, style = type.titleMd, color = c.ink)
                Text(caption, style = type.caption, color = c.inkMuted)
            }
        }
        content()
    }
}

/** How much space photos and videos take: a segmented bar and a legend with each part's size. */
@Composable
private fun StorageCard(parts: List<Pair<StorageKind, Long>>, onStorage: () -> Unit) {
    val c = AlbumTheme.colors
    val context = LocalContext.current
    fun colorOf(kind: StorageKind) = when (kind) {
        StorageKind.Photos -> c.ink
        StorageKind.Videos -> c.flashInk
        StorageKind.WhatsApp -> c.lineStrong
        StorageKind.Screenshots -> c.line
    }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes) // KB, MB or GB as fits, in the phone's locale
    InfoCard(Sym.Storage, "Storage", "${size(parts.sumOf { it.second })} used by photos and videos") {
        // The legend below says the same in words, so the bar itself needs nothing for TalkBack
        Row(Modifier.fillMaxWidth().height(StorageBarHeight).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.forEach { (kind, bytes) -> Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(colorOf(kind))) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            parts.forEach { (kind, bytes) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Box(Modifier.size(LegendDot).background(colorOf(kind), CircleShape))
                    Text("${kind.label} ${size(bytes)}", style = AlbumTheme.type.caption, color = c.inkMuted)
                }
            }
        }
        AlbumButton("See storage", onStorage, Modifier.fillMaxWidth())
    }
}
