package com.aditya.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform

/**
 * Things and places open through the same album id (a Long) as folders, [Special] albums and collections, so Explore
 * reuses the album screen with its selection, viewer and empty state. The ranges never meet: folder ids are BUCKET_IDs
 * (the Int range), Special ids are small multiples of 2^32, things are 2^40 plus the category's ordinal, places are
 * 2^44 plus a 32-bit hash of the place, and collections start at 2^48.
 */
private const val THING_IDS = 1L shl 40
private const val PLACE_IDS = 1L shl 44
private const val PLACE_HASHES = 0xFFFF_FFFFL

fun thingIdOf(category: Category) = THING_IDS + category.ordinal

/** The category behind an album id, or null for any other album. */
fun thingOf(albumId: Long): Category? = (albumId - THING_IDS).takeIf { it in 0L until Category.entries.size }?.let { Category.entries[it.toInt()] }

// ponytail: a 32-bit hash, like BUCKET_ID. With 100 places the chance that two share an id is about one in a million,
// and then one opens the other. A place id column in the index would rule it out.
fun placeIdOf(place: Place) = PLACE_IDS + (place.hashCode().toLong() and PLACE_HASHES)

fun isPlaceId(albumId: Long) = albumId - PLACE_IDS in 0..PLACE_HASHES

/** A thing or place and its photos, newest first. The newest is its cover. */
data class Group<K, T>(val key: K, val items: List<T>)

/**
 * Joins the index's ids for each key with [media] (newest first) in one pass over each. Groups keep [media]'s order;
 * empty ones (deleted, or in a folder hidden from Photos) drop out. Biggest first, ties in [ids]' order.
 * An id belongs to one group at most: a photo has one category and one place.
 */
fun <K, T> groupsOf(ids: Map<K, Set<Long>>, media: List<T>, idOf: (T) -> Long): List<Group<K, T>> {
    val keyOf = HashMap<Long, K>()
    ids.forEach { (key, set) -> set.forEach { keyOf[it] = key } }
    val items = HashMap<K, MutableList<T>>()
    for (m in media) keyOf[idOf(m)]?.let { items.getOrPut(it) { ArrayList() } += m }
    return ids.keys.mapNotNull { key -> items[key]?.let { Group(key, it) } }.sortedByDescending { it.items.size }
}

/** Places by country, the country with most photos first. Each country keeps its places' order. */
fun <T> byCountry(places: List<Group<Place, T>>): List<List<Group<Place, T>>> =
    places.groupBy { it.key.countryCode }.values.sortedByDescending { country -> country.sumOf { it.items.size } }

/** What Explore calls each place: its town, plus the state when another place has a town of the same name (Aurangabad). */
fun placeNames(places: List<Place>): Map<Place, String> {
    val towns = places.groupingBy { it.city }.eachCount()
    return places.associateWith { if (towns.getValue(it.city) > 1 && it.admin1.isNotEmpty()) "${it.city}, ${it.admin1}" else it.city }
}

// The index changes every few seconds while it runs; Explore re-reads it at most this often.
private const val REFRESH_MS = 3_000L

/**
 * Places and things joined with the photos Photos shows, shared by the Explore screens and the albums they open.
 * Null until first loaded. Works only while something on screen collects it, off the main thread.
 */
object Explore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Explore organises the timeline, so folders hidden from Photos stay out of it too, as in the calendar and search.
    private val photos = combine(Gallery.media.filterNotNull(), AlbumPrefs.hidden) { media, hidden -> media.inPhotos(hidden) }

    /** Biggest first. Other says nothing about a photo, so it isn't shown. */
    val things: StateFlow<List<Group<Category, Media>>?> =
        joined(SmartIndex.categories().distinctUntilChanged().map { counts -> counts.map { it.category } - Category.Other }) {
            SmartIndex.mediaIdsFor(it)
        }

    /** Towns, biggest first. */
    val places: StateFlow<List<Group<Place, Media>>?> =
        joined(SmartIndex.places().distinctUntilChanged().map { counts -> counts.map { it.place } }) { SmartIndex.mediaIdsFor(it) }

    // ponytail: one query per key on each refresh, fine for dozens of towns. If libraries reach hundreds, one
    // "id, category, place" query in SmartIndex would replace them.
    private fun <K> joined(keys: Flow<List<K>>, idsOf: (K) -> Flow<Set<Long>>): StateFlow<List<Group<K, Media>>?> {
        val ids = keys.conflate().transform { list ->
            emit(list.associateWith { idsOf(it).first() })
            delay(REFRESH_MS)
        }
        return combine(ids, photos) { idsByKey, timeline -> groupsOf(idsByKey, timeline) { it.id } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null) // a quick tab switch keeps it running
    }
}

private const val EMPTY_THING = "Nothing here yet. Photos show up here as Album learns what's in them."

/** A thing or place opened as an album. */
class SmartAlbum(val title: String, val items: List<Media>, val emptyText: String = EMPTY_THING)

/** The thing or place behind album id [id] with its photos. Null for other ids, and while Explore is loading. */
@Composable
fun rememberSmartAlbum(id: Long): SmartAlbum? {
    if (thingOf(id) == null && !isPlaceId(id)) return null // a folder, Special or collection: leaves the index alone
    val things = Explore.things.collectAsState().value
    val places = Explore.places.collectAsState().value
    return remember(id, things, places) {
        val thing = thingOf(id)
        when {
            thing != null -> things?.let { SmartAlbum(thing.title, it.find { g -> g.key == thing }?.items.orEmpty()) }
            // A place with no photos left drops out of the list, so its album closes like an emptied folder's
            else -> places?.let { all ->
                all.find { placeIdOf(it.key) == id }?.let { SmartAlbum(placeNames(all.map { p -> p.key }).getValue(it.key), it.items) }
            }
        }
    }
}

private const val PLACES_IN_ROW = 12

/**
 * The Explore tab: indexing progress, People, Places, Things and Utilities. Every tile opens through [onOpen] with an
 * album id. [people] is the People section (PeopleSection from the people work), drawn above Places.
 */
@Composable
fun ExploreScreen(
    albums: List<Album>,
    specialCounts: Map<Special, Int>,
    state: LazyGridState,
    contentPadding: PaddingValues,
    onOpen: (Long) -> Unit,
    onAllPlaces: () -> Unit,
    people: (@Composable () -> Unit)? = null,
) {
    val progress by SmartIndex.progress.collectAsState()
    val places = Explore.places.collectAsState().value
    val things = Explore.things.collectAsState().value
    val names = remember(places) { placeNames(places.orEmpty().map { it.key }) }
    // Utilities open the same albums as the Albums tab, so like there they include folders hidden from Photos
    val utilities = remember(albums, specialCounts, things) {
        val screenshots = albums.filter { it.name.equals("Screenshots", ignoreCase = true) }.maxByOrNull { it.count }
        listOfNotNull(
            Utility("Videos", Icons.Outlined.VideoLibrary, Special.Videos.id, specialCounts[Special.Videos] ?: 0),
            screenshots?.let { Utility("Screenshots", Icons.Outlined.Screenshot, it.id, it.count) },
            Utility("Documents", Icons.Outlined.Description, thingIdOf(Category.Documents), things?.find { it.key == Category.Documents }?.items?.size ?: 0),
            Utility("Recently deleted", Icons.Outlined.Delete, Special.Trash.id, specialCounts[Special.Trash] ?: 0),
        )
    }
    LazyVerticalGrid(
        GridCells.Fixed(3),
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (progress.done < progress.total) item(key = "progress", span = { GridItemSpan(maxLineSpan) }) { Organising(progress) }
        people?.let { section -> item(key = "people", span = { GridItemSpan(maxLineSpan) }) { section() } }
        item(key = "places", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Places", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (!places.isNullOrEmpty()) TextButton(onClick = {
                    CrashLog.note("all places")
                    onAllPlaces()
                }) { Text("See all") }
            }
        }
        item(key = "place row", span = { GridItemSpan(maxLineSpan) }) {
            when {
                places == null -> {} // loading: a moment on first open only
                places.isEmpty() -> Hint("Places appear for photos with a location")
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(places.take(PLACES_IN_ROW), key = { placeKey(it.key) }) {
                        Tile(it.items.first(), names.getValue(it.key), it.items.size, Modifier.width(120.dp)) { openPlace(it.key, onOpen) }
                    }
                }
            }
        }
        item(key = "things", span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Things") }
        when {
            things == null -> {}
            things.isEmpty() -> item(key = "things hint", span = { GridItemSpan(maxLineSpan) }) {
                Hint("Food, pets, sunsets and more appear here as Album organises your photos")
            }
            else -> items(things, key = { thingIdOf(it.key) }) {
                Tile(it.items.first(), it.key.title, it.items.size, Modifier.fillMaxWidth()) {
                    CrashLog.note("open thing ${it.key.name}")
                    onOpen(thingIdOf(it.key))
                }
            }
        }
        item(key = "utilities", span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Utilities") }
        item(key = "utility rows", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                utilities.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { UtilityChip(it, Modifier.weight(1f), onOpen) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Explore's "See all" places, grouped by country with the country holding most photos first. */
@Composable
fun PlacesScreen(state: LazyGridState, contentPadding: PaddingValues, onOpen: (Long) -> Unit) {
    val places = Explore.places.collectAsState().value
    val countries = remember(places) { byCountry(places.orEmpty()) }
    val names = remember(places) { placeNames(places.orEmpty().map { it.key }) }
    if (places?.isEmpty() == true) {
        Message("Places appear for photos with a location", Modifier.padding(contentPadding))
        return
    }
    LazyVerticalGrid(
        GridCells.Fixed(3),
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        countries.forEach { country ->
            val first = country.first().key
            item(key = "country ${first.countryCode}", span = { GridItemSpan(maxLineSpan) }) { SectionHeader(first.country) }
            items(country, key = { placeKey(it.key) }) {
                Tile(it.items.first(), names.getValue(it.key), it.items.size, Modifier.fillMaxWidth()) { openPlace(it.key, onOpen) }
            }
        }
    }
}

// Lazy keys must be unique, so they use the whole place rather than its hashed album id
private fun placeKey(place: Place) = "${place.countryCode}/${place.admin1}/${place.city}"

private fun openPlace(place: Place, onOpen: (Long) -> Unit) {
    CrashLog.note("open place") // no name: crash logs never say where you've been
    onOpen(placeIdOf(place))
}

/** Same look as a folder tile in Albums: square cover, name, count. */
@Composable
private fun Tile(cover: Media, name: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        AsyncImage(
            Thumb(cover.uri),
            contentDescription = null,
            Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(LocalLook.current.cellCorner + 8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Text(name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private class Utility(val title: String, val icon: ImageVector, val albumId: Long, val count: Int)

@Composable
private fun UtilityChip(u: Utility, modifier: Modifier, onOpen: (Long) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(LocalLook.current.cellCorner + 8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                CrashLog.note("open utility ${u.title}")
                onOpen(u.albumId)
            }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(u.icon, null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(u.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${u.count}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shown while the smart index works through the photos; gone once it's done. */
@Composable
private fun Organising(progress: IndexProgress) {
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp)
            .clip(RoundedCornerShape(LocalLook.current.cellCorner + 8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text(
            "Organising your photos: ${"%,d".format(progress.done)} of ${"%,d".format(progress.total)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator({ progress.done / progress.total.toFloat() }, Modifier.fillMaxWidth().padding(top = 10.dp))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
