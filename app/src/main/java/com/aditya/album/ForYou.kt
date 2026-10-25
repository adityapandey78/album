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

// The For you tab: memories made on this phone from the Library (on this day, this week last year, last month's recap)
// and where the storage goes. Everything here is worked out from the photo list; nothing is indexed or uploaded.

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
    val week: Memory<Media>?,
    val recap: Memory<Media>?,
    val storage: List<Pair<StorageKind, Long>>,
)

private fun forYouOf(media: List<Media>, today: LocalDate): ForYou {
    // Memories play as photo stories; videos would need their own length
    val photos = media.filter { !it.isVideo }
    return ForYou(
        onThisDay(photos, today, Media::day),
        weekLastYear(photos, today, Media::day),
        monthRecap(photos, today, Media::day),
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
 * as a story with its title, eyebrow and up to [STORY_LENGTH] photos.
 */
@Composable
fun ForYouScreen(
    photos: List<Media>,
    state: LazyListState,
    contentPadding: PaddingValues,
    onPlay: (title: String, eyebrow: String, items: List<Media>) -> Unit,
) {
    // The app can stay open overnight; "on this day" moves on when it comes back
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleResumeEffect(Unit) {
        today = LocalDate.now()
        onPauseOrDispose {}
    }
    // A pass over the whole Library (20k photos on a big phone) stays off the main thread, so switching tabs never stutters
    val model by produceState<ForYou?>(null, photos, today) {
        value = withContext(Dispatchers.Default) { forYouOf(photos, today) }
    }
    LazyColumn(Modifier.fillMaxSize(), state, contentPadding) {
        item(key = "title") { LargeTopBar("For you", subtitle = "Made on this phone, from your photos") }
        val m = model ?: return@LazyColumn
        item(key = "cards") {
            Column(Modifier.padding(horizontal = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                fun play(memory: Memory<Media>) = onPlay(memory.title, memory.eyebrow, spread(memory.items, STORY_LENGTH))
                m.onThisDay?.let { MemoryCard(it, big = true, onClick = { play(it) }, Modifier.fillMaxWidth()) }
                val pair = listOfNotNull(m.week, m.recap)
                if (pair.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        pair.forEach { MemoryCard(it, big = false, onClick = { play(it) }, Modifier.weight(1f)) }
                    }
                }
                if (m.onThisDay == null && pair.isEmpty()) {
                    EmptyState(
                        Sym.Favorite, "No memories yet",
                        "Photos from this day in earlier years and recaps of past months show up here.",
                    )
                }
                if (m.storage.isNotEmpty()) StorageCard(m.storage)
            }
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

/** How much space photos and videos take: a segmented bar and a legend with each part's size. */
@Composable
private fun StorageCard(parts: List<Pair<StorageKind, Long>>) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val context = LocalContext.current
    val shape = RoundedCornerShape(Space.radiusLg)
    fun colorOf(kind: StorageKind) = when (kind) {
        StorageKind.Photos -> c.ink
        StorageKind.Videos -> c.flashInk
        StorageKind.WhatsApp -> c.lineStrong
        StorageKind.Screenshots -> c.line
    }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes) // KB, MB or GB as fits, in the phone's locale
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Box(Modifier.size(Space.target).background(c.flashSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Sym.Storage, null, tint = c.flashInk)
            }
            Column {
                Text("Storage", style = type.titleMd, color = c.ink)
                Text("${size(parts.sumOf { it.second })} used by photos and videos", style = type.caption, color = c.inkMuted)
            }
        }
        // The legend below says the same in words, so the bar itself needs nothing for TalkBack
        Row(Modifier.fillMaxWidth().height(StorageBarHeight).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.forEach { (kind, bytes) -> Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(colorOf(kind))) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            parts.forEach { (kind, bytes) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Box(Modifier.size(LegendDot).background(colorOf(kind), CircleShape))
                    Text("${kind.label} ${size(bytes)}", style = type.caption, color = c.inkMuted)
                }
            }
        }
    }
}
