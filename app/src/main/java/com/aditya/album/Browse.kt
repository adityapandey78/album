package com.aditya.album

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Browse: what Album sorted on this phone (people and pets, places, things, videos), a strip of each, shown in the
// Collections tab. Things' See all opens the Things page; the others open People, Places and Videos.

/** A strip shows this many; See all shows the rest. */
private const val IN_STRIP = 12
private val STRIP_CARD = 96.dp
private val VIDEO_TILE = 112.dp

/** "food, pets and sunsets": names the way a sentence lists them. */
fun listed(names: List<String>): String = if (names.size < 2) names.joinToString() else "${names.dropLast(1).joinToString(", ")} and ${names.last()}"

/** Things' line in Browse: how many, and the first three, "8 things, like food, pets and sunsets". */
fun thingsLine(titles: List<String>): String =
    counted(titles.size, "thing", "things") + if (titles.isEmpty()) "" else ", like ${listed(titles.take(3).map { it.lowercase() })}"

/** What the Browse sections show: people and pets, places, things and videos, read once for the screen showing them. */
class BrowseData(
    val people: List<PersonAlbum>,
    val peopleLine: String,
    val anyHidden: Boolean,
    val places: List<Group<Place, Media>>?,
    val placeNames: Map<Place, String>,
    val placesLine: String?,
    val things: List<Group<Category, Media>>?,
    val videos: List<Media>,
    val videosLine: String,
)

@Composable
fun rememberBrowse(media: List<Media>): BrowseData {
    val context = LocalContext.current
    val everyone by People.albums.collectAsState()
    val scanning by People.progress.collectAsState()
    val reviews by remember { People.review }.collectAsState(emptyList())
    val places = Explore.places.collectAsState().value
    val things = Explore.things.collectAsState().value
    // People first, then pets, as the section's name has them
    val people = remember(everyone) { shownPeople(everyone).sortedBy { it.species != null } }
    val names = remember(places) { placeNames(places.orEmpty().map { it.key }) }
    val states = remember(places) { placeSections(places.orEmpty()).size }
    val videos = remember(media) { media.filter { it.isVideo } }
    return BrowseData(
        people = people,
        peopleLine = when {
            people.isNotEmpty() -> peopleSubtitle(people, 0) + if (reviews.isEmpty()) "" else " · ${reviewCount(reviews.size)}"
            scanning.total > 0 -> scanningText(scanning)
            else -> "Album looks for faces in your photos, on this phone"
        },
        anyHidden = remember(everyone) { everyone.any { it.hidden && !it.locked } },
        places = places,
        placeNames = names,
        placesLine = when {
            places == null -> null // loading: a moment on first open only
            places.isEmpty() -> "Places appear for photos with a location"
            else -> "${counted(places.size, "place", "places")} in ${counted(states, "state", "states")}"
        },
        things = things,
        videos = videos,
        videosLine = if (videos.isEmpty()) Special.Videos.emptyText
        else "${counted(videos.size, "video", "videos")} · ${Formatter.formatShortFileSize(context, videos.sumOf { it.size })}",
    )
}

/**
 * People and pets, Places, Things and Videos, each a heading with a strip of its first few, as rows of the Collections
 * tab's grid. A heading opens all of it ([onPeople], [onPlaces], [onThings], the Videos album through [onOpen]); a card
 * opens its album through [onOpen]. Every person, place and thing card on the tab opens from here.
 */
fun LazyGridScope.browseSections(b: BrowseData, onOpen: (Long) -> Unit, onPeople: () -> Unit, onPlaces: () -> Unit, onThings: () -> Unit) {
    val open = { what: String, id: Long ->
        CrashLog.note("open $what")
        onOpen(id)
    }
    fullRow("people") {
        // See all stays while only hidden people are left, so hiding can be undone
        BrowseSection(Sym.Person, "People and pets", b.peopleLine, onPeople.takeIf { b.people.isNotEmpty() || b.anyHidden }) {
            items(b.people.take(IN_STRIP), key = { it.cluster }) { p -> PersonCard(p, Modifier.width(STRIP_CARD)) { open("person", p.albumId) } }
        }
    }
    fullRow("places") {
        BrowseSection(Sym.LocationOn, "Places", b.placesLine, onPlaces.takeIf { !b.places.isNullOrEmpty() }) {
            items(b.places.orEmpty().take(IN_STRIP), key = { placeKey(it.key) }) {
                PlaceCard(it, b.placeNames.getValue(it.key), Modifier.width(STRIP_CARD)) { openPlace(it.key, onOpen) }
            }
        }
    }
    fullRow("things") {
        val things = b.things
        val line = when {
            things == null -> null
            things.isEmpty() -> "Food, pets, sunsets and more appear here as Album organises your photos"
            else -> thingsLine(things.map { it.key.title })
        }
        BrowseSection(Sym.Category, "Things", line, onThings.takeIf { !things.isNullOrEmpty() }) {
            items(things.orEmpty().take(IN_STRIP), key = { it.key.name }) { t -> ThingCard(t, Modifier.width(STRIP_CARD)) { open("thing ${t.key.name}", thingIdOf(t.key)) } }
        }
    }
    fullRow("videos") {
        val all = { open("videos", Special.Videos.id) }
        BrowseSection(Sym.Videocam, "Videos", b.videosLine, all.takeIf { b.videos.isNotEmpty() }, gap = AlbumTheme.spec.gutter) {
            items(b.videos.take(6), key = { it.id }) { m ->
                MediaTile(m, selecting = false, selected = false, modifier = Modifier.size(VIDEO_TILE).clickable(role = Role.Button, onClickLabel = "Open Videos", onClick = all))
            }
        }
    }
}

/**
 * A Browse section: its icon, [title] and [line], and See all when [onSeeAll] is set (the whole heading opens it), then
 * a strip of [cards] scrolling sideways edge to edge.
 */
@Composable
private fun BrowseSection(sym: Sym, title: String, line: String?, onSeeAll: (() -> Unit)?, gap: Dp = Space.s3, cards: LazyListScope.() -> Unit) {
    val c = AlbumTheme.colors
    Column(Modifier.padding(top = Space.s6)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Space.target + Space.s2)
                .then(if (onSeeAll != null) Modifier.clickable(role = Role.Button, onClickLabel = "See all", onClick = onSeeAll) else Modifier)
                .padding(horizontal = Space.s4, vertical = Space.s2),
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(sym)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = AlbumTheme.type.titleMd, color = c.ink)
                line?.let { Text(it, style = AlbumTheme.type.caption, color = c.inkMuted) }
            }
            if (onSeeAll != null) Text("See all", style = AlbumTheme.type.label, color = c.flashInk)
        }
        LazyRow(
            contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = Space.s2),
            horizontalArrangement = Arrangement.spacedBy(gap),
            content = cards,
        )
    }
}

/** A thing (food, pets, documents) as an AlbumCard: its newest photo, its name and how many photos. */
@Composable
private fun ThingCard(thing: Group<Category, Media>, modifier: Modifier, onClick: () -> Unit) {
    val n = thing.items.size
    AlbumCard(thing.key.title, listOf("%,d".format(n)), "${thing.key.title}, ${itemCount(n)}", onClick, modifier) {
        Thumbnail(thing.items.first(), Modifier.matchParentSize())
    }
}

/** Every thing Album found, biggest first: Browse's Things See all, pushed over the tabs. */
@Composable
fun ThingsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val things = Explore.things.collectAsState().value
    PushedGridPage("Things", things?.let { counted(it.size, "thing", "things") }, rememberLazyGridState(), onBack, collapsed = true) {
        if (things?.isEmpty() == true) fullRow("empty") {
            EmptyState(Sym.Category, "No things yet", "Food, pets, sunsets and more appear here as Album organises your photos.")
        }
        items(things.orEmpty(), key = { it.key.name }) { t ->
            ThingCard(t, Modifier.padding(bottom = Space.s4)) {
                CrashLog.note("open thing ${t.key.name}")
                onOpen(thingIdOf(t.key))
            }
        }
    }
}
