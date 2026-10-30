package com.aditya.album

import android.app.Activity
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One category as Clean up shows it: [runs] of tiles, each starting a row of its own. Copies and similar shots have a run
 * per group, the one that stays first; the other categories have one run.
 */
class CleanupSection<T>(val kind: CleanupKind, val runs: List<List<T>>)

/**
 * The chosen [kinds]' sections, in chip order. [lookup] finds an item, or null once it's gone (trashed, hidden): gone items
 * are left out, and so is a group left with one.
 */
fun <T> cleanupSections(result: CleanupResult, kinds: Collection<CleanupKind>, lookup: (Long) -> T?): List<CleanupSection<T>> =
    CleanupKind.entries.filter { it in kinds }.mapNotNull { kind ->
        val runs = when (kind) {
            CleanupKind.Copies, CleanupKind.Similar -> result.groups(kind).mapNotNull { g -> g.ids.mapNotNull(lookup).takeIf { it.size > 1 } }
            else -> listOf(result.candidates(kind).mapNotNull(lookup)).filter { it.isNotEmpty() }
        }
        if (runs.isEmpty()) null else CleanupSection(kind, runs)
    }

/**
 * Every tile of [sections] once, in screen order: what the viewer swipes through from a photo opened here. An original
 * that's also one of several similar shots shows in both sections; the viewer has it at its first place.
 */
fun <T> tilesOf(sections: List<CleanupSection<T>>, idOf: (T) -> Long): List<T> = sections.flatMap { it.runs.flatten() }.distinctBy(idOf)

/**
 * Clean up, pushed over the tabs: a chip per category with its count, and the chosen categories' photos in a grid, a
 * section each under a line saying why they're there. The photo that stays in each group (Original, Best shot) is never
 * suggested. Cleaning up is picking: Select in the top bar, a long-press on a tile, or a section's "Select N", which picks
 * what that category suggests. The SelectionBar moves the picked photos to Trash in one request, with Undo; a copy's
 * original that stays takes over its favourite and collections first. A tap opens a photo, and the viewer swipes through
 * this screen's tiles in their order: [report] gets them, as album pages report theirs. [category] (a [CleanupKind] name,
 * as For you and Storage pass) opens on that category alone; otherwise on every suggestion.
 */
@Composable
fun CleanupScreen(
    media: List<Media>,
    onBack: () -> Unit,
    onOpen: (Media) -> Unit,
    category: String? = null,
    report: (List<Media>) -> Unit = {},
) {
    val c = AlbumTheme.colors
    val context = LocalContext.current
    val result by Cleanup.result.collectAsState()
    val byId = remember(media) { media.associateBy { it.id } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    // Kind names, so the choice survives the activity being recreated. A name no kind has any more (a saved route from
    // before a rename) opens on the suggestions rather than on nothing.
    var chosen by rememberSaveable {
        mutableStateOf(
            category?.takeIf { name -> CleanupKind.entries.any { it.name == name } }?.let(::listOf)
                ?: CleanupKind.entries.filter { it.suggested }.map { it.name },
        )
    }
    val kinds = CleanupKind.entries.filter { it.name in chosen }
    val sections = remember(result, chosen, byId) { result?.let { r -> cleanupSections(r, kinds, byId::get) }.orEmpty() }
    val tiles = remember(sections) { tilesOf(sections) { it.id } }
    DisposableEffect(tiles) {
        report(tiles)
        onDispose {}
    }
    // What each category suggests, and how many of those are in the Library, for the chips
    val suggests = remember(result) { result?.let { r -> CleanupKind.entries.filter { it.suggested }.associateWith { r.candidates(it).toHashSet() } }.orEmpty() }
    val counts = remember(result, byId) { result?.let { r -> CleanupKind.entries.associateWith { k -> r.candidates(k).count { it in byId } } }.orEmpty() }
    val suggested = remember(sections, suggests) { sections.flatMap { s -> suggestedIn(s, suggests) }.distinctBy { it.id } }

    // Picking, by tile or by section. Ids, so it survives the activity being recreated.
    var selectMode by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    // Only tiles on screen count: photos gone meanwhile, or in a category no longer chosen, drop out
    val picked = remember(selected, tiles) { selected.toHashSet().let { ids -> tiles.filter { it.id in ids } } }
    val pickedIds = remember(picked) { picked.mapTo(HashSet()) { it.id } }
    val selecting = selectMode || picked.isNotEmpty()
    fun clearSelection() {
        selectMode = false
        selected = emptyList()
    }
    fun pick(items: List<Media>, on: Boolean) {
        val ids = items.mapTo(HashSet()) { it.id }
        selected = if (on) (selected + ids).distinct() else selected - ids
        selectMode = on || pickedIds.any { it !in ids } // unpicking the last one ends selecting, as in the design
    }

    // One trip to Trash, while Android asks about favourites and then about Trash: every item going, and the copies among
    // them with each one's original, in the same order. Ids, so a step in between survives the activity being recreated.
    var going by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var copies by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var originals by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    fun forget() {
        going = emptyList()
        copies = emptyList()
        originals = emptyList()
    }
    // The selection ends once Android has moved the photos: declining its pop-up keeps it to try again
    val trash = rememberTrashWithUndo(snackbar) {
        clearSelection()
        val moved = copies
        val kept = originals
        forget()
        if (moved.isNotEmpty()) scope.launch { keepCollections(moved, kept) }
    }
    // Android asks to favourite the originals of favourite copies first; the items go only once it has
    val favourite = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) trash(going.mapNotNull(byId::get)) else forget()
    }

    /** Moves [out] to Trash. Copies among it hand their favourite and collections to the original that stays. */
    fun send(out: List<Media>) {
        val pairs = result?.originalsOf(out.map { it.id }).orEmpty()
            .mapNotNull { (original, copy) -> byId[original]?.let { o -> byId[copy]?.let { o to it } } }
        going = out.map { it.id }
        copies = pairs.map { it.second.id }
        originals = pairs.map { it.first.id }
        val favourites = pairs.filter { (original, copy) -> copy.isFavorite && !original.isFavorite }.map { it.first }.distinct()
        if (favourites.isEmpty()) {
            trash(out)
        } else {
            favourite.launch(IntentSenderRequest.Builder(favoriteRequest(context.contentResolver, favourites, true)).build())
        }
    }

    BackHandler { if (selecting) clearSelection() else onBack() }

    val subtitle = when {
        result == null -> "Looking through your photos"
        picked.isNotEmpty() -> "Frees ${size(picked.sumOf { it.size })}"
        suggested.isNotEmpty() -> "${counted(suggested.size, "item to review", "items to review")} · frees ${size(suggested.sumOf { it.size })}"
        else -> itemCount(tiles.size)
    }
    val allPicked = suggested.isNotEmpty() && suggested.all { it.id in pickedIds }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.statusBarsPadding()) {
                CollapsedTopBar("Clean up", subtitle = subtitle, onBack = { if (selecting) clearSelection() else onBack() }) {
                    if (tiles.isNotEmpty() && !selecting) AlbumIconButton(Sym.CheckCircle, "Select", { selectMode = true })
                }
            }
            val r = result
            if (r == null) {
                EmptyState(
                    Sym.CleaningServices, "Looking through your photos",
                    "Copies, similar shots and blurry photos show up here as they're found. Nothing leaves this phone.",
                )
            } else {
                CleanupGrid(
                    r, sections, kinds, counts, suggests,
                    onToggle = { kind -> chosen = if (kind.name in chosen) chosen - kind.name else chosen + kind.name },
                    picking = TilePicking(selecting, pickedIds,
                        onTap = { m ->
                            when {
                                !selecting -> onOpen(m)
                                else -> pick(listOf(m), m.id !in pickedIds)
                            }
                        },
                        onHold = { m ->
                            haptics.longPress()
                            pick(listOf(m), true)
                        },
                    ),
                    onPick = ::pick,
                    bottomPadding = bottom + if (selecting) SelectionClearance else Space.s8,
                    size = ::size,
                )
            }
        }
        AnimatedVisibility(
            selecting,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.s4),
            enter = if (AlbumTheme.spec.still) fadeIn(tween(0)) else slideInVertically { it } + fadeIn(),
            exit = if (AlbumTheme.spec.still) fadeOut(tween(0)) else slideOutVertically { it } + fadeOut(),
        ) {
            SelectionBar(
                picked.size,
                listOf(
                    SelAction(Sym.Delete, "Move to Trash", danger = true) {
                        if (picked.isNotEmpty() && context.canSend(picked.size, MAX_REQUEST, "delete")) {
                            CrashLog.note("cleanup trash ${picked.size}")
                            send(picked)
                        }
                    },
                ),
                onClose = ::clearSelection,
                // What the shown categories suggest: never an original or a best shot, nothing from WhatsApp or Big videos
                onSelectAll = if (suggested.isEmpty()) null else ({ pick(if (allPicked) picked else suggested, !allPicked) }),
                allLabel = if (allPicked) "Clear all" else "Select suggested",
            )
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = Space.s4 + if (selecting) SelectionBarHeight + Space.s2 else 0.dp),
        ) { AlbumSnackbar(it) }
    }
}

// What [section] suggests, of what it shows: in a group, everything but what stays
private fun suggestedIn(section: CleanupSection<Media>, suggests: Map<CleanupKind, Set<Long>>): List<Media> {
    val ids = suggests[section.kind] ?: return emptyList()
    return section.runs.flatten().filter { it.id in ids }
}

/** Album's collections that held one of [copies] now hold its original ([originals], in the same order) as well. */
private suspend fun keepCollections(copies: List<Long>, originals: List<Long>) {
    val originalOf = copies.zip(originals).toMap()
    try {
        Collections.items.first().filter { it.mediaId in originalOf }.groupBy { it.collectionId }.forEach { (id, items) ->
            Collections.add(id, "", items.map { originalOf.getValue(it.mediaId) }.distinct())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CrashLog.note("cleanup collections failed: $e") // the copies are in Trash either way; Undo brings them back
    }
}

/** Why a photo is in [kind], in one line under its heading. */
private fun noteOf(kind: CleanupKind) = when (kind) {
    CleanupKind.Copies -> "Saved more than once: the same file, a smaller or shared copy, or a screenshot of it. The original of each stays."
    CleanupKind.Similar -> "Camera shots taken seconds apart. The best one stays; the others are suggested."
    CleanupKind.Exposure -> "Camera shots that came out nearly black or washed out, like pocket shots."
    CleanupKind.Blurry -> "Camera shots that look out of focus or shaken."
    CleanupKind.Screenshots -> "Screenshots more than 30 days old. Most are needed only for a moment."
    CleanupKind.BigVideos -> "Videos over 100 MB, biggest first. Nothing here is suggested: pick what you don't need."
    CleanupKind.WhatsApp -> "Photos and videos saved from WhatsApp chats. Nothing here is suggested: pick what you don't need."
}

/**
 * The chips and, under them, each chosen category's section: its heading with what it would free and a button that picks
 * what it suggests ([onPick]), the line saying why, then its tiles.
 */
@Composable
private fun CleanupGrid(
    result: CleanupResult,
    sections: List<CleanupSection<Media>>,
    kinds: List<CleanupKind>,
    counts: Map<CleanupKind, Int>,
    suggests: Map<CleanupKind, Set<Long>>,
    onToggle: (CleanupKind) -> Unit,
    picking: TilePicking,
    onPick: (List<Media>, Boolean) -> Unit,
    bottomPadding: Dp,
    size: (Long) -> String,
) {
    val c = AlbumTheme.colors
    val spec = AlbumTheme.spec
    // Chips for categories with something in them, and any chosen one, so it can be unchosen
    val chips = CleanupKind.entries.filter { (counts[it] ?: 0) > 0 || it in kinds }
    if (chips.isEmpty()) {
        EmptyState(Sym.CheckCircle, "Nothing to clean up", "No copies, similar shots, blurry photos or old screenshots right now.")
        return
    }
    val dark = remember(result) { result.dark.toHashSet() }
    LazyVerticalGrid(
        GridCells.Fixed(DEFAULT_COLUMNS),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Space.s3, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(spec.gutter),
        verticalArrangement = Arrangement.spacedBy(spec.gutter),
    ) {
        item(key = "chips", span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(contentPadding = PaddingValues(horizontal = Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                items(chips, key = { it.name }) { kind ->
                    Chip(
                        kind.label, { onToggle(kind) }, selected = kind in kinds, count = "%,d".format(counts[kind] ?: 0),
                        leading = { KindIcon(kind, size = Space.iconSm, tint = c.ink) },
                    )
                }
            }
        }
        if (sections.isEmpty()) {
            item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(Sym.CheckCircle, "Nothing here", "Choose a category above.")
            }
        }
        for (section in sections) {
            val kind = section.kind
            val shown = if (kind.suggested) suggestedIn(section, suggests) else section.runs.flatten()
            item(key = "head:$kind", span = { GridItemSpan(maxLineSpan) }) {
                val all = shown.isNotEmpty() && shown.all { it.id in picking.selected }
                Column(
                    Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = Space.s6, bottom = Space.s1),
                    verticalArrangement = Arrangement.spacedBy(Space.s1),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(kind.label, style = AlbumTheme.type.titleMd, color = c.ink)
                            val bytes = size(shown.sumOf { it.size })
                            Text("${itemCount(shown.size)} · ${if (kind.suggested) "frees" else "uses"} $bytes", style = AlbumTheme.type.caption, color = c.inkMuted)
                        }
                        // Browse categories suggest nothing, so there's nothing to pick in one go
                        if (kind.suggested && shown.isNotEmpty()) {
                            val n = "%,d".format(shown.size)
                            AlbumButton(if (all) "Unselect $n" else "Select $n", { onPick(shown, !all) }, variant = ButtonVariant.Quiet)
                        }
                    }
                    Text(noteOf(kind), style = AlbumTheme.type.caption, color = c.inkMuted)
                }
            }
            val keeps = result.groups(kind).mapTo(HashSet()) { it.keep }
            for (run in section.runs) {
                // Each group starts on a row of its own
                if (keeps.isNotEmpty()) item(key = "gap:$kind:${run.first().id}", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(Space.s2)) }
                cleanupTiles(kind, run, picking) { m ->
                    when (kind) {
                        CleanupKind.Copies -> if (m.id in keeps) "Original" to BadgeTone.Flash else null
                        CleanupKind.Similar -> when (m.id) {
                            in keeps -> "Best shot" to BadgeTone.Flash
                            in result.originals -> "Original" to BadgeTone.Flash // has copies: shared or saved, so it stays too
                            else -> null
                        }
                        CleanupKind.Exposure -> (if (m.id in dark) "Too dark" else "Too bright") to BadgeTone.Photo
                        CleanupKind.BigVideos -> size(m.size) to BadgeTone.Photo
                        else -> null // the heading says it
                    }
                }
            }
        }
    }
}

/** How the grid's tiles answer touch: a tap opens, or picks while [selecting]; a long-press ([onHold]) starts picking. */
private class TilePicking(val selecting: Boolean, val selected: Set<Long>, val onTap: (Media) -> Unit, val onHold: (Media) -> Unit)

// Keys carry the kind: an original that's also a similar shot is a tile in both sections (picked in both, since picking goes by photo)
private fun LazyGridScope.cleanupTiles(kind: CleanupKind, tiles: List<Media>, picking: TilePicking, badge: (Media) -> Pair<String, BadgeTone>?) {
    items(tiles, key = { "$kind:${it.id}" }) { m ->
        val b = badge(m)
        MediaTile(
            m, picking.selecting, m.id in picking.selected,
            Modifier.aspectRatio(1f).combinedClickable(
                onClickLabel = if (picking.selecting) null else "Open",
                onLongClickLabel = "Select",
                onLongClick = { picking.onHold(m) },
                hapticFeedbackEnabled = false, // Haptics follows Album's own haptics switch
            ) { picking.onTap(m) },
            badge = b?.first,
            badgeTone = b?.second ?: BadgeTone.Photo,
            checkSize = checkSizeFor(DEFAULT_COLUMNS),
        )
    }
}
