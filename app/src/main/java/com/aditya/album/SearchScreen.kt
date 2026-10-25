package com.aditya.album

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
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

/** The top bar while searching: Back and the query field, focused when search opens. */
@Composable
fun SearchBar(search: SearchState, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Only a fresh search brings the keyboard up, not coming back to one after selecting or rotating
    LaunchedEffect(Unit) { if (search.query.text.isEmpty()) focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        val clear = Color.Transparent
        TextField(
            value = search.query,
            onValueChange = { search.query = it },
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text("Search your photos") },
            trailingIcon = if (search.query.text.isEmpty()) null else {
                {
                    IconButton(onClick = {
                        search.query = TextFieldValue()
                        focus.requestFocus()
                    }) { Icon(Icons.Outlined.Close, "Clear") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                search.keep()
                focusManager.clearFocus() // hides the keyboard so the results show
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = clear,
                unfocusedContainerColor = clear,
                focusedIndicatorColor = clear,
                unfocusedIndicatorColor = clear,
            ),
        )
    }
}

/**
 * Below the search bar: suggestions while the query is empty, then what it was read as and the photos it found.
 * [columns], [grid] and the selection belong to AlbumShell, like every grid's, so they survive the viewer.
 */
@Composable
fun SearchContent(
    search: SearchState,
    columns: MutableIntState,
    grid: LazyGridState,
    selected: Set<Long>,
    onOpen: (Media) -> Unit,
    onSelect: (Set<Long>) -> Unit,
    padding: PaddingValues,
    gridModifier: Modifier,
) {
    val focusManager = LocalFocusManager.current
    val progress by SmartIndex.progress.collectAsState()
    val note = indexingNote(progress)
    val result = search.result
    val text = search.query.text
    LaunchedEffect(text) { grid.requestScrollToItem(0) } // a new query starts at the top; back from the viewer keeps the place
    // Scrolling the results means reading them: the keyboard goes
    LaunchedEffect(grid) { snapshotFlow { grid.isScrollInProgress }.collect { if (it) focusManager.clearFocus() } }
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        when {
            text.isBlank() -> Suggestions(search, note) {
                search.search(it)
                focusManager.clearFocus()
            }
            result == null -> {} // the first search takes a moment
            result.items.isEmpty() -> NoResults(result, note)
            else -> {
                MatchLine(result, note)
                MediaGrid(
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
                    PaddingValues(bottom = padding.calculateBottomPadding()),
                    gridModifier,
                )
            }
        }
    }
}

@Composable
private fun Suggestions(search: SearchState, note: String?, onPick: (String) -> Unit) {
    val places = remember(search.places) { search.places.map { it.place.city }.distinct().take(MAX_SUGGESTIONS) }
    val things = remember(search.things) { search.things.map { it.category }.filter { it != Category.Other }.take(MAX_SUGGESTIONS) }
    Column(Modifier.fillMaxSize().aboveKeyboard().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (search.recent.isNotEmpty()) {
            SectionTitle("Recent") { TextButton(onClick = search::clearRecent) { Text("Clear") } }
            Chips(search.recent, Icons.Outlined.History, onPick)
        }
        if (places.isNotEmpty()) {
            SectionTitle("Places")
            Chips(places, Icons.Outlined.Place, onPick)
        }
        if (things.isNotEmpty()) {
            SectionTitle("Things")
            Chips(things.map { it.title }, Icons.Outlined.Category, onPick)
        }
        Text(
            listOfNotNull(TIP, note).joinToString("\n\n"),
            Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SectionTitle(text: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        action()
    }
}

@Composable
private fun Chips(texts: List<String>, icon: ImageVector, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        texts.forEach { text ->
            SuggestionChip(
                onClick = { onPick(text) },
                label = { Text(text) },
                icon = { Icon(icon, null, Modifier.size(SuggestionChipDefaults.IconSize)) },
            )
        }
    }
}

/** "[Goa] [Food] [2024]   42 results": what the query was read as, so a surprising result explains itself. */
@Composable
private fun MatchLine(result: SearchResult, note: String?) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), Arrangement.spacedBy(6.dp)) {
                result.terms.forEach { term ->
                    Row(
                        Modifier.background(colors.secondaryContainer, CircleShape).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                            .semantics(mergeDescendants = true) {}, // read as "Place, Goa"
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(term.kind.icon, term.kind.name, Modifier.size(16.dp), tint = colors.onSecondaryContainer)
                        Text(term.label, color = colors.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Text(
                if (result.items.size == 1) "1 result" else "%,d results".format(result.items.size),
                Modifier.padding(start = 12.dp),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        note?.let { Text(it, Modifier.padding(top = 6.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun NoResults(result: SearchResult, note: String?) {
    val title = when {
        result.unmatched.isNotEmpty() -> "Nothing found for ${namesOf(result.unmatched.map { it.label })}"
        result.terms.size > 1 -> "Nothing matches ${namesOf(result.terms.map { it.label })} together"
        else -> "Nothing found"
    }
    Column(
        Modifier.fillMaxSize().aboveKeyboard().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            listOfNotNull(TIP, note).joinToString("\n\n"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

// The navigation bar's padding is used up first, so with the keyboard up only the rest of its height is added
private fun Modifier.aboveKeyboard() = navigationBarsPadding().imePadding()

/** "Goa", "Goa and Food", "Goa, Food and 2024". */
fun namesOf(names: List<String>): String = if (names.size < 2) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()

/** Why results may be missing photos: the smart index is still going. Null once it's done. */
private fun indexingNote(p: IndexProgress) =
    if (p.done >= p.total) null else "Album is still going through your photos (%,d of %,d), so some can't be found yet.".format(p.done, p.total)

private val MatchKind.icon
    get() = when (this) {
        MatchKind.Thing -> Icons.Outlined.Category
        MatchKind.Place -> Icons.Outlined.Place
        MatchKind.Date -> Icons.Outlined.Event
        MatchKind.Album -> Icons.Outlined.PhotoAlbum
        MatchKind.Type -> Icons.Outlined.PermMedia
        MatchKind.Text -> Icons.Outlined.TextFields
        MatchKind.Person -> Icons.Outlined.Person
    }
