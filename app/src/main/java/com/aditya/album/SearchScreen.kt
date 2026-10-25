package com.aditya.album

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

private const val DEBOUNCE_MS = 150L // typing pauses this long before a search runs, so fast typing doesn't queue searches
private const val RECENT_KEY = "search_recent" // in the "settings" preferences, which AlbumApp loads at start
private const val MAX_RECENT = 8
private const val MAX_SUGGESTIONS = 8
private const val TIP = "Try a place, a thing like food or dogs, a date like March 2024 or last week, or words from a screenshot."

/** What a search found: its terms (for the chips), the matching photos newest first, and the terms that found nothing. */
class SearchResult(val terms: List<Term>, val items: List<Media>, val unmatched: List<Term>, val ms: Long)

/**
 * An open search. AlbumShell holds it and shows [items] like an album's, so the selection bar, the viewer and Back work
 * on results as they do everywhere else.
 */
@Stable
class SearchState(
    query: MutableState<TextFieldValue>,
    things: State<List<CategoryCount>>,
    places: State<List<PlaceCount>>,
    private val prefs: SharedPreferences,
) {
    var query by query
    val things by things // most photos first, for suggestions
    val places by places
    var result by mutableStateOf<SearchResult?>(null) // the latest finished search; kept while the next one runs
    var recent by mutableStateOf(prefs.getString(RECENT_KEY, null)?.split('\n').orEmpty())
        private set

    /** What the results grid shows. Reads only [result], so typing doesn't recompose AlbumShell, which shows these. */
    val items: List<Media> get() = result?.items.orEmpty()

    /** Searches for [text] as if typed: a suggestion chip. */
    fun search(text: String) {
        query = TextFieldValue(text, TextRange(text.length))
        saveRecent()
    }

    /** Remembers the query as a recent search: on Search or opening a result. */
    fun keep() {
        result?.let { CrashLog.note("search: ${it.terms.size} terms, ${it.items.size} found, ${it.ms} ms") } // never the query itself
        saveRecent()
    }

    private fun saveRecent() {
        recent = withRecent(recent, query.text, MAX_RECENT)
        prefs.edit { putString(RECENT_KEY, recent.joinToString("\n")) }
    }

    fun clearRecent() {
        recent = emptyList()
        prefs.edit { remove(RECENT_KEY) }
    }
}

/** Recent searches with [query] first, not repeated (ignoring case), at most [max]. */
fun withRecent(recent: List<String>, query: String, max: Int): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return recent
    return (listOf(q) + recent.filterNot { it.equals(q, ignoreCase = true) }).take(max)
}

/**
 * Search, while it's open. [photos] is what Photos shows, so folders hidden from Photos stay out of results too.
 * Results follow the query as you type and refresh as the smart index finds more.
 */
@Composable
fun rememberSearch(photos: List<Media>, collections: List<CollectionAlbum>): SearchState {
    val context = LocalContext.current
    val query = rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val things = remember { SmartIndex.categories() }.collectAsState(emptyList())
    val places = remember { SmartIndex.places() }.collectAsState(emptyList())
    val labels = remember { SmartIndex.labels() }.collectAsState(emptyList())
    val people = remember { People.mediaIdsByName() }.collectAsState(emptyMap()) // named, not hidden
    val search = remember { SearchState(query, things, places, context.getSharedPreferences("settings", Context.MODE_PRIVATE)) }
    val library = remember(photos, collections) { Library(photos, collections) }
    // The query and the index are read here, not in composition: this runs inside AlbumShell, which shouldn't recompose
    // on every keystroke or index batch. Equal snapshots (a batch that changed no counts) don't search again.
    LaunchedEffect(library) {
        snapshotFlow { SearchInput(query.value.text, things.value, places.value, labels.value, people.value) }.collectLatest { input ->
            if (input.text.isBlank()) {
                search.result = null // so a new query never flashes the results of the old one
                return@collectLatest
            }
            delay(DEBOUNCE_MS) // a new keystroke cancels the wait
            search.result = withContext(Dispatchers.Default) { library.search(input.text, input.things, input.places, input.labels, input.people) }
        }
    }
    return search
}

private data class SearchInput(
    val text: String,
    val things: List<CategoryCount>,
    val places: List<PlaceCount>,
    val labels: List<String>,
    val people: Map<String, Set<Long>>,
)

/** The photos as search sees them, prepared on the first search after they change (off the main thread). */
private class Library(private val photos: List<Media>, private val collections: List<CollectionAlbum>) {
    private val items by lazy {
        photos.map { SearchItem(it.id, it.day, it.isVideo, it.isFavorite, !it.isVideo && isScreenshot(it.path, it.name), it.name.lowercase()) }
    }

    // Folders and collections, by any part of their name
    private val albums by lazy {
        NamedSource(
            MatchKind.Album,
            photos.groupBy { it.bucketId }.values.map { album ->
                Named(album[0].bucketName, partsOf(album[0].bucketName)) { album.mapTo(HashSet()) { it.id } }
            } + collections.map { c -> Named(c.name, partsOf(c.name)) { c.items.mapTo(HashSet()) { it.id } } },
        )
    }

    fun search(
        query: String,
        things: List<CategoryCount>,
        places: List<PlaceCount>,
        labels: List<String>,
        people: Map<String, Set<Long>>,
    ): SearchResult {
        val started = SystemClock.elapsedRealtime()
        val sources = listOf(
            // "aditya" finds "Aditya Pandey"; a person's photos are only gathered when the name matched
            NamedSource(MatchKind.Person, people.map { (name, ids) -> Named(name, partsOf(name)) { ids } }),
            thingSource(things.map { it.category }, labels, SmartIndex::idsIn, SmartIndex::idsWith),
            placeSource(places.map { it.place }, SmartIndex::idsAt),
            albums,
            typeSource,
            textSource(SmartIndex::idsWithText),
            fileSource,
        )
        val terms = parseQuery(query, DateSource(LocalDate.now(), WeekFields.of(Locale.getDefault()).firstDayOfWeek), sources)
        val found = if (terms.isEmpty()) emptyList() else matching(items, terms).map { photos[it] } // only stop words: nothing
        val unmatched = if (found.isEmpty()) unmatched(items, terms) else emptyList()
        return SearchResult(terms, found, unmatched, SystemClock.elapsedRealtime() - started)
    }
}

/**
 * Search, over the tabs. The field sits at the bottom, where the search disc was, so the thumb never travels to the top;
 * suggestions, then what the query was read as and the photos it found, fill the screen above it.
 * [columns], [grid] and the selection belong to AlbumShell, like every grid's, so they survive the viewer.
 */
@Composable
fun SearchPage(
    search: SearchState,
    columns: MutableIntState,
    grid: LazyGridState,
    selected: Set<Long>,
    onOpen: (Media) -> Unit,
    onSelect: (Set<Long>) -> Unit,
    selecting: Boolean,
    onBack: () -> Unit,
    gridModifier: Modifier,
) {
    val c = AlbumTheme.colors
    val focusManager = LocalFocusManager.current
    val progress by SmartIndex.progress.collectAsState()
    val note = indexingNote(progress)
    val result = search.result
    val text = search.query.text
    LaunchedEffect(text) { grid.requestScrollToItem(0) } // a new query starts at the top; back from the viewer keeps the place
    // Scrolling the results means reading them: the keyboard goes
    LaunchedEffect(grid) { snapshotFlow { grid.isScrollInProgress }.collect { if (it) focusManager.clearFocus() } }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 57.dp // the one-row title bar and its hairline
    // Content clears the field, and the keyboard when it is up
    val bottom = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding() + FIELD_HEIGHT + Space.s4 + Space.s8
    val padding = PaddingValues(top = top, bottom = bottom)
    Box(Modifier.fillMaxSize().background(c.canvas)) {
        when {
            text.isBlank() -> Suggestions(search, note, padding) {
                search.search(it)
                focusManager.clearFocus()
            }
            result == null -> {} // the first search takes a moment
            result.items.isEmpty() -> NoResults(result, note, padding)
            else -> MediaGrid(
                result.items,
                columns,
                grid,
                selected,
                onOpen = {
                    focusManager.clearFocus()
                    search.keep()
                    onOpen(it)
                },
                onSelect,
                padding,
                gridModifier,
                header = { MatchLine(result, note) },
            )
        }
        Column(Modifier.fillMaxWidth().background(c.canvas).statusBarsPadding()) {
            CollapsedTopBar(
                if (text.isBlank()) "Search" else "\u201c${text.trim()}\u201d",
                subtitle = if (text.isBlank()) "Runs on this phone" else result?.let { "${countOf(it.items.size, "photo")} · on-device" },
                onBack = onBack,
            )
        }
        if (!selecting) {
            SearchField(
                search,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(start = Space.s4, end = Space.s4, bottom = Space.s4),
            ) {
                search.keep()
                focusManager.clearFocus() // hides the keyboard so the results show
            }
        }
    }
}

private val FIELD_HEIGHT = 52.dp

/** The search field: it stretches out of the search disc at the right, at the same height, as search opens. */
@Composable
private fun SearchField(search: SearchState, modifier: Modifier, onSearch: () -> Unit) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val focus = remember { FocusRequester() }
    // Only a fresh search brings the keyboard up, not coming back to one after selecting or rotating
    LaunchedEffect(Unit) { if (search.query.text.isEmpty()) focus.requestFocus() }
    val grow = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(320, easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f))) }
    Row(
        modifier.fillMaxWidth().height(FIELD_HEIGHT)
            .drawWithContent {
                // the disc's width at the right edge grows to the full width
                val left = (size.width - Space.bar.toPx()).coerceAtLeast(0f) * (1f - grow.value)
                // only the left edge moves; the other sides stay open so the shadow isn't cut square
                clipRect(left = left, top = -size.height, right = size.width * 2, bottom = size.height * 2) { this@drawWithContent.drawContent() }
            }
            .floating().background(c.surfaceHigh, CircleShape)
            .padding(start = Space.s4, end = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.Search, null, tint = c.ink)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (search.query.text.isEmpty()) {
                Text("Places, things, people or dates", style = AlbumTheme.type.body, color = c.inkMuted, maxLines = 1)
            }
            BasicTextField(
                value = search.query,
                onValueChange = { search.query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search photos" },
                textStyle = AlbumTheme.type.body.copy(color = c.ink),
                singleLine = true,
                cursorBrush = SolidColor(c.flashInk),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
        }
        if (search.query.text.isNotEmpty()) {
            AlbumIconButton(Sym.Close, "Clear", {
                search.query = TextFieldValue()
                focus.requestFocus()
            })
        } else {
            Badge("On-device", Modifier.padding(end = Space.s2), BadgeTone.Soft, Sym.Shield)
        }
    }
}

@Composable
private fun Suggestions(search: SearchState, note: String?, padding: PaddingValues, onPick: (String) -> Unit) {
    val places = remember(search.places) { search.places.map { it.place.city }.distinct().take(MAX_SUGGESTIONS) }
    val things = remember(search.things) { search.things.map { it.category }.filter { it != Category.Other }.take(MAX_SUGGESTIONS) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
        if (search.recent.isNotEmpty()) {
            SectionHead("Recent", action = "Clear", onAction = search::clearRecent)
            Chips(search.recent, Sym.History, onPick)
        }
        if (places.isNotEmpty()) {
            SectionHead("Places")
            Chips(places, Sym.LocationOn, onPick)
        }
        if (things.isNotEmpty()) {
            SectionHead("Things")
            Chips(things.map { it.title }, Sym.Category, onPick)
        }
        Text(
            listOfNotNull(TIP, note).joinToString("\n\n"),
            Modifier.padding(horizontal = Space.s4, vertical = Space.s6),
            color = AlbumTheme.colors.inkMuted,
            style = AlbumTheme.type.body,
        )
    }
}

@Composable
private fun Chips(texts: List<String>, icon: Sym, onPick: (String) -> Unit) {
    FlowRow(
        Modifier.padding(horizontal = Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        texts.forEach { text -> Chip(text, { onPick(text) }, icon = icon, toggle = false) }
    }
}

/** "[Goa] [Food] [2024]   42 results": what the query was read as, so a surprising result explains itself. */
@Composable
private fun MatchLine(result: SearchResult, note: String?) {
    val c = AlbumTheme.colors
    Column(Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s2, bottom = Space.s3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), Arrangement.spacedBy(Space.s2)) {
                result.terms.forEach { term ->
                    Row(
                        Modifier.background(c.flashSoft, CircleShape).padding(start = Space.s2, end = Space.s3, top = 6.dp, bottom = 6.dp)
                            .semantics(mergeDescendants = true) {}, // read as "Place, Goa"
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(term.kind.sym, term.kind.name, size = 16.dp, tint = c.flashInk)
                        Text(term.label, color = c.ink, style = AlbumTheme.type.label)
                    }
                }
            }
            Text(countOf(result.items.size, "result"), Modifier.padding(start = Space.s3), color = c.inkMuted, style = AlbumTheme.type.meta)
        }
        note?.let { Text(it, Modifier.padding(top = 6.dp), color = c.inkMuted, style = AlbumTheme.type.caption) }
    }
}

@Composable
private fun NoResults(result: SearchResult, note: String?, padding: PaddingValues) {
    val title = when {
        result.unmatched.isNotEmpty() -> "Nothing found for ${namesOf(result.unmatched.map { it.label })}"
        result.terms.size > 1 -> "Nothing matches ${namesOf(result.terms.map { it.label })} together"
        else -> "Nothing found"
    }
    Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
        EmptyState(Sym.SearchOff, title, listOfNotNull(TIP, note).joinToString("\n\n"))
    }
}

/** "Goa", "Goa and Food", "Goa, Food and 2024". */
fun namesOf(names: List<String>): String = if (names.size < 2) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()

/** Why results may be missing photos: the smart index is still going. Null once it's done. */
private fun indexingNote(p: IndexProgress) =
    if (p.done >= p.total) null else "Album is still going through your photos (%,d of %,d), so some can't be found yet.".format(p.done, p.total)

private val MatchKind.sym
    get() = when (this) {
        MatchKind.Thing -> Sym.Category
        MatchKind.Place -> Sym.LocationOn
        MatchKind.Date -> Sym.Event
        MatchKind.Album -> Sym.PhotoAlbum
        MatchKind.Type -> Sym.Image
        MatchKind.Text -> Sym.TextFields
        MatchKind.Person -> Sym.Person
    }
