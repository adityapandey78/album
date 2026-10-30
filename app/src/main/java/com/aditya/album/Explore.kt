package com.aditya.album

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
 * Things and places open through the same album id (a Long) as folders, [Special] albums and collections, so they
 * reuse the album screen with its selection, viewer and empty state. The ranges never meet: folder ids are BUCKET_IDs
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

/** One state's cities on the Places page, under [title]. */
class PlaceSection<T>(val title: String, val countryCode: String, val places: List<Group<Place, T>>)

/**
 * The Places page's sections: a state each, with its cities. Countries with most photos come first, then within each its
 * states with most photos, each keeping its cities' order (biggest first). The country with most photos is home, so its
 * states go by their names alone ("Goa"); others add their country ("Île-de-France, France"). A place GeoNames gives no
 * state goes under its country's name, together with a state of the same name (Djibouti's), so titles never repeat
 * within a country.
 */
fun <T> placeSections(places: List<Group<Place, T>>): List<PlaceSection<T>> = byCountry(places).flatMapIndexed { i, country ->
    fun titleOf(p: Place) = when {
        p.admin1.isEmpty() || p.admin1 == p.country -> p.country
        i == 0 -> p.admin1
        else -> "${p.admin1}, ${p.country}"
    }
    country.groupBy { titleOf(it.key) }.entries.sortedByDescending { (_, state) -> state.sumOf { it.items.size } }
        .map { (title, state) -> PlaceSection(title, state[0].key.countryCode, state) }
}

/** What Album calls each place: its town, plus the state when another place has a town of the same name (Aurangabad). */
fun placeNames(places: List<Place>): Map<Place, String> {
    val towns = places.groupingBy { it.city }.eachCount()
    return places.associateWith { if (towns.getValue(it.city) > 1 && it.admin1.isNotEmpty()) "${it.city}, ${it.admin1}" else it.city }
}

// The index changes every few seconds while it runs; places and things re-read it at most this often.
private const val REFRESH_MS = 3_000L

/**
 * Places and things joined with the photos Photos shows, shared by Collections, the Places page and the albums they open.
 * Null until first loaded. Works only while something on screen collects it, off the main thread.
 */
object Explore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Places and things organise the Library, so folders hidden from it stay out of them too, as in the calendar and search.
    // Places and things organise the Library, so folders hidden from it stay out of them too, as in the calendar and
    // search, and so does everything in Hidden items
    val photos = combine(Gallery.media.filterNotNull(), AlbumPrefs.hidden, Hidden.state.filterNotNull()) { media, hidden, h ->
        media.inPhotos(hidden).withoutHidden(h)
    }

    /** Biggest first. Other says nothing about a photo, so it isn't shown. */
    val things: StateFlow<List<Group<Category, Media>>?> =
        joined(SmartIndex.categories().distinctUntilChanged().map { counts -> counts.map { it.category } - Category.Other }) {
            SmartIndex.mediaIdsFor(it)
        }

    /** Cities (the city level of each place), biggest first. */
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

/** The thing or place behind album id [id] with its photos. Null for other ids, and while places and things are loading. */
@Composable
fun rememberSmartAlbum(id: Long): SmartAlbum? {
    if (isMapAreaId(id)) return rememberMapArea(id)
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

/** Every city, in a section per state (see placeSections): Collections' "See all", pushed over the tabs. Map opens [onMap]. */
@Composable
fun PlacesScreen(state: LazyGridState, onBack: () -> Unit, onOpen: (Long) -> Unit, onMap: () -> Unit) {
    val places = Explore.places.collectAsState().value
    val sections = remember(places) { placeSections(places.orEmpty()) }
    val names = remember(places) { placeNames(places.orEmpty().map { it.key }) }
    val countries = remember(sections) { sections.distinctBy { it.countryCode }.size }
    val subtitle = places?.let { "${counted(it.size, "place", "places")} · ${counted(countries, "country", "countries")}" }
    PushedGridPage("Places", subtitle, state, onBack, actions = { if (places?.isNotEmpty() == true) AlbumIconButton(Sym.Map, "Map", onMap) }) {
        if (places?.isEmpty() == true) fullRow("empty") { EmptyState(Sym.LocationOn, "No places yet", "Places appear for photos with a location.") }
        sections.forEach { section ->
            fullRow("section ${section.countryCode}/${section.title}") { SectionHead(section.title) } // titles are one per country
            items(section.places, key = { placeKey(it.key) }) {
                PlaceCard(it, names.getValue(it.key), Modifier.padding(bottom = Space.s4)) { openPlace(it.key, onOpen) }
            }
        }
    }
}

/** A place as an AlbumCard: its newest photo, its name and how many photos. */
@Composable
fun PlaceCard(place: Group<Place, Media>, name: String, modifier: Modifier, onClick: () -> Unit) {
    val n = place.items.size
    AlbumCard(name, listOf("%,d".format(n)), "$name, ${itemCount(n)}", onClick, modifier) { Thumbnail(place.items.first(), Modifier.matchParentSize()) }
}

// Lazy keys must be unique, so they use the whole place rather than its hashed album id
fun placeKey(place: Place) = "${place.countryCode}/${place.admin1}/${place.city}"

fun openPlace(place: Place, onOpen: (Long) -> Unit) {
    CrashLog.note("open place") // no name: crash logs never say where you've been
    onOpen(placeIdOf(place))
}
