package com.aditya.album

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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

/** "Try describing": what the describe-it search reads, as the design suggests it. */
private val DESCRIBING = listOf("sunset at the beach", "red car", "birthday cake", "invoice")

/** Words in photos that are worth finding again, as chips once any photo has them: the papers people photograph. */
private val DOCUMENT_WORDS = listOf(
    "invoice", "tax invoice", "receipt", "bill", "ticket", "boarding pass", "Aadhaar", "passport", "licence", "warranty",
    "prescription", "menu",
)

/** Of [words], those found in any photo ([count] says in how many), most first, at most [max]. */
fun wordsInPhotos(words: List<String>, max: Int, count: (String) -> Int): List<String> =
    words.map { it to count(it) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(max).map { it.first }

/**
 * What a search found: its terms (for the chips), the best matches best first, the rest newest first, the terms that
 * found nothing (for an empty result) and the words left out because nothing knows them.
 */
class SearchResult(
    val terms: List<Term>,
    val top: List<Media>,
    val more: List<Media>,
    val unmatched: List<Term>,
    val ignored: List<Term>,
    val ms: Long,
) {
    /** Every result in the order shown: what the viewer swipes through. */
    val items = top + more
}

/**
 * An open search. AlbumShell holds it and shows [items] like an album's, so the selection bar, the viewer and Back work
 * on results as they do everywhere else.
 */
@Stable
class SearchState(
    query: MutableState<TextFieldValue>,
    things: State<List<CategoryCount>>,
    places: State<List<PlaceCount>>,
    people: State<Map<String, Set<Long>>>,
    private val prefs: SharedPreferences,
) {
    var query by query
    val things by things // most photos first, for suggestions
    val places by places
    val people by people // named people (not hidden) and their photos
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
        result?.let { CrashLog.note("search: ${it.terms.size} terms, ${it.items.size} found (${it.top.size} top), ${it.ms} ms") } // never the query itself
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
    val placeNames = remember { SmartIndex.placeNames() }.collectAsState(emptyList())
    val labels = remember { SmartIndex.labels() }.collectAsState(emptyList())
    val people = remember { People.mediaIdsByName() }.collectAsState(emptyMap()) // named, not hidden
    val search = remember { SearchState(query, things, places, people, context.getSharedPreferences("settings", Context.MODE_PRIVATE)) }
    val library = remember(photos, collections) { Library(photos, collections) }
    // The query and the index are read here, not in composition: this runs inside AlbumShell, which shouldn't recompose
    // on every keystroke or index batch. Equal snapshots (a batch that changed no counts) don't search again.
    LaunchedEffect(library) {
        snapshotFlow { SearchInput(query.value.text, things.value, placeNames.value, labels.value, people.value) }.collectLatest { input ->
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
    val places: List<String>,
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

    suspend fun search(
        query: String,
        things: List<CategoryCount>,
        places: List<String>,
        labels: List<String>,
        people: Map<String, Set<Long>>,
    ): SearchResult {
        val started = SystemClock.elapsedRealtime()
        val sources = listOf(
            // "aditya" finds "Aditya Pandey"; a person's photos are only gathered when the name matched
            NamedSource(MatchKind.Person, people.map { (name, ids) -> Named(name, partsOf(name)) { ids } }),
            thingSource(things.map { it.category }, labels, SmartIndex::scoredIn, SmartIndex::scoredWith),
            placeSource(places, SmartIndex.placeAliases, SmartIndex::idsAt),
            albums,
            typeSource,
            textSource(SmartIndex::idsWithTokens),
            fileSource,
        )
        val dates = DateSource(LocalDate.now(), WeekFields.of(Locale.getDefault()).firstDayOfWeek)
        val terms = parseQuery(query, dates, sources, exactTextSource(SmartIndex::idsWithPhrase))
        // The describe-it model reads what the photos show: the words that aren't a place, date, person or album. It keeps
        // only hits it's confident of itself (its calibrated floor), best first; ids outside this library are ignored.
        val described = terms.filter { it.isThing || it.loose }.joinToString(" ") { it.text }.takeIf { it.isNotBlank() }
            ?.let { Embeddings.search(it) }?.map { it.first }
        val found = find(items, terms, described) // only stop words: no terms, nothing found
        return SearchResult(
            terms, found.top.map { photos[it] }, found.more.map { photos[it] }, unmatched(items, terms, found), found.ignored,
            SystemClock.elapsedRealtime() - started,
        )
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
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 80.dp // under the one-row title bar, as the design spaces it
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
            else -> {
                val open = { m: Media ->
                    focusManager.clearFocus()
                    search.keep()
                    onOpen(m)
                }
                // The rest are the grid's own photos, newest first with date headers; the top matches sit above them, and
                // words left out or an index still going say so first
                MediaGrid(
                    result.more,
                    columns,
                    grid,
                    selected,
                    onOpen = open,
                    onSelect,
                    padding,
                    gridModifier,
                    header = {
                        Column {
                            Notes(listOfNotNull(leftOut(result.ignored), note))
                            TopMatches(result, columns.intValue, selected, selecting, open, onSelect)
                        }
                    },
                )
            }
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

/**
 * The search field: a flat pill that stretches out of the search disc at the right, at the same height, as search opens.
 * Focused, a flash-ink ring sits 2dp outside it. On-device always shows; Clear comes before it once there's a query.
 */
@Composable
private fun SearchField(search: SearchState, modifier: Modifier, onSearch: () -> Unit) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // Only a fresh search brings the keyboard up, not coming back to one after selecting or rotating
    LaunchedEffect(Unit) { if (search.query.text.isEmpty()) focus.requestFocus() }
    val grow = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(320, easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f))) }
    Row(
        modifier.fillMaxWidth().height(FIELD_HEIGHT)
            .drawWithContent {
                // the disc at the right edge stretches left into the full pill
                val left = (size.width - Space.bar.toPx()).coerceAtLeast(0f) * (1f - grow.value)
                val round = CornerRadius(size.height / 2)
                clipPath(Path().apply { addRoundRect(RoundRect(left, 0f, size.width, size.height, round)) }) { this@drawWithContent.drawContent() }
                if (focused) {
                    val out = 3.dp.toPx() // a 2dp ring, 2dp out
                    drawRoundRect(
                        c.flashInk, Offset(left - out, -out), Size(size.width - left + 2 * out, size.height + 2 * out),
                        CornerRadius(size.height / 2 + out), style = Stroke(2.dp.toPx()),
                    )
                }
            }
            .background(c.surfaceHigh, CircleShape)
            .padding(start = Space.s4, end = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.Search, null, tint = c.ink)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (search.query.text.isEmpty()) {
                Text("Describe a photo, or words in it", style = AlbumTheme.type.body, color = c.inkMuted, maxLines = 1)
            }
            BasicTextField(
                value = search.query,
                onValueChange = { search.query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "Search photos" },
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
        }
        Badge("On-device", tone = BadgeTone.Soft, icon = Sym.Shield)
    }
}

/**
 * Before typing: Try describing, then Recent, People, Places, Things and Words in photos, each from this phone's own photos
 * (a section with nothing in it stays out), and a note while the index is still going.
 */
@Composable
private fun Suggestions(search: SearchState, note: String?, padding: PaddingValues, onPick: (String) -> Unit) {
    val people = remember(search.people) { search.people.entries.sortedByDescending { it.value.size }.map { it.key }.take(MAX_SUGGESTIONS) }
    val places = remember(search.places) { search.places.map { it.place.city }.distinct().take(MAX_SUGGESTIONS) }
    val things = remember(search.things) { search.things.map { it.category }.filter { it != Category.Other }.take(MAX_SUGGESTIONS) }
    // One quick full-text lookup a word, off the main thread
    val words by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            wordsInPhotos(DOCUMENT_WORDS, MAX_SUGGESTIONS) { runCatching { SmartIndex.idsWithPhrase(ftsTokens(it)).size }.getOrDefault(0) }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
        SectionHead("Try describing")
        Chips(DESCRIBING, Sym.Search, onPick)
        if (search.recent.isNotEmpty()) {
            SectionHead("Recent", action = "Clear", onAction = search::clearRecent)
            Chips(search.recent, Sym.History, onPick)
        }
        if (people.isNotEmpty()) {
            SectionHead("People")
            Chips(people, Sym.Person, onPick)
        }
        if (places.isNotEmpty()) {
            SectionHead("Places")
            Chips(places, Sym.LocationOn, onPick)
        }
        if (things.isNotEmpty()) {
            SectionHead("Things")
            Chips(things.map { it.title }, Sym.Category, onPick)
        }
        if (words.isNotEmpty()) {
            SectionHead("Words in photos")
            Chips(words, Sym.ContentCopy, onPick)
        }
        note?.let { Text(it, Modifier.padding(horizontal = Space.s4, vertical = Space.s6), color = AlbumTheme.colors.inkMuted, style = AlbumTheme.type.body) }
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

/** Captions above the results, when there's something to say: words left out, the index still going. */
@Composable
private fun Notes(notes: List<String>) {
    if (notes.isEmpty()) return
    Column(Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s2, bottom = Space.s3), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        notes.forEach { Text(it, color = AlbumTheme.colors.inkMuted, style = AlbumTheme.type.caption) }
    }
}

/** "“zzz” matched nothing, so it's left out": a word nothing knows no longer empties the search, but it says so. */
fun leftOut(ignored: List<Term>): String? = when (ignored.size) {
    0 -> null
    1 -> "${ignored[0].label} matched nothing, so it's left out"
    else -> "${namesOf(ignored.map { it.label })} matched nothing, so they're left out"
}

/**
 * The best matches above the rest, as a block of the grid's own tiles (same columns and gutter): tap opens, long-press
 * selects, as in the grid. Only when the search had something to rank by (labels, the model, several words found).
 */
@Composable
private fun TopMatches(
    result: SearchResult,
    columns: Int,
    selected: Set<Long>,
    selecting: Boolean,
    onOpen: (Media) -> Unit,
    onSelect: (Set<Long>) -> Unit,
) {
    if (result.top.isEmpty()) return
    val gutter = AlbumTheme.spec.gutter
    val haptics = rememberHaptics()
    val toggle = { m: Media -> onSelect(if (m.id in selected) selected - m.id else selected + m.id) }
    TierHead("Top matches", Modifier.padding(top = Space.s1))
    Column(verticalArrangement = Arrangement.spacedBy(gutter)) {
        result.top.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gutter)) {
                row.forEach { m ->
                    MediaTile(
                        m, selecting, m.id in selected,
                        Modifier.weight(1f).aspectRatio(1f).combinedClickable(
                            interactionSource = null,
                            indication = null, // as in the grid: the photo opening (or its check popping) is the feedback
                            onLongClickLabel = "Select",
                            onLongClick = {
                                haptics.longPress()
                                toggle(m)
                            },
                            hapticFeedbackEnabled = false, // Haptics follows Album's own haptics switch
                        ) { if (selecting) toggle(m) else onOpen(m) },
                        small = columns >= 5,
                        checkSize = checkSizeFor(columns),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (result.more.isNotEmpty()) TierHead("More results", Modifier.padding(top = Space.s8))
}

/** "Top matches", "More results": quieter than the date headers under them. */
@Composable
private fun TierHead(text: String, modifier: Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, bottom = Space.s3).semantics { heading() },
        style = AlbumTheme.type.label,
        color = AlbumTheme.colors.inkMuted,
    )
}

/** No matches, at the top as in the design, saying which words found nothing where that's why. */
@Composable
private fun NoResults(result: SearchResult, note: String?, padding: PaddingValues) {
    val known = result.terms - result.ignored.toSet()
    val why = when {
        result.unmatched.isNotEmpty() -> "Nothing found for ${namesOf(result.unmatched.map { it.label })}."
        known.size > 1 -> "Nothing matches ${namesOf(known.map { it.label })} together."
        else -> null
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        EmptyState(Sym.Search, "No matches", listOfNotNull(why, "Try a thing, a place, a person, a year, or words in a photo.", note).joinToString(" "))
    }
}

/** "Goa", "Goa and Food", "Goa, Food and 2024". */
fun namesOf(names: List<String>): String = if (names.size < 2) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()

/** Why results may be missing photos: the smart index is still going. Null once it's done. */
private fun indexingNote(p: IndexProgress) =
    if (p.done >= p.total) null else "Album is still going through your photos (%,d of %,d), so some can't be found yet.".format(p.done, p.total)
