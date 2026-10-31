package com.aditya.album

import android.provider.MediaStore
import android.app.Activity
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Tiles per row in Clean up's grid, and in Storage's biggest items. */
const val CLEAN_COLUMNS = 4

/** Clean up's first chip: a look at every category at once, rather than one [CleanupKind] (whose names the others are). */
private const val ALL = "All"

/** Room under the list for the docked "Select all suggested" button. */
private val DockClearance = 56.dp + 20.dp + Space.s8

/**
 * One category as Clean up shows it: [runs] of tiles, each starting a row of its own. Copies and similar shots have a run
 * per group, the one that stays first; the other categories have one run.
 */
class CleanupSection<T>(val kind: CleanupKind, val runs: List<List<T>>)

/** Copies and similar shots come in groups, each around the one that stays; the other categories are plain lists. */
val CleanupKind.grouped get() = this == CleanupKind.Copies || this == CleanupKind.Similar

/**
 * The chosen [kinds]' sections, in chip order. [lookup] finds an item, or null once it's gone (trashed, hidden): gone items
 * are left out, and so is a group left with one.
 */
fun <T> cleanupSections(result: CleanupResult, kinds: Collection<CleanupKind>, lookup: (Long) -> T?): List<CleanupSection<T>> =
    CleanupKind.entries.filter { it in kinds }.mapNotNull { kind ->
        val runs = if (kind.grouped) {
            result.groups(kind).mapNotNull { g -> g.ids.mapNotNull(lookup).takeIf { it.size > 1 } }
        } else {
            listOf(result.candidates(kind).mapNotNull(lookup)).filter { it.isNotEmpty() }
        }
        if (runs.isEmpty()) null else CleanupSection(kind, runs)
    }

/** What All shows of [section]: its first group, or its first row of tiles. */
fun <T> previewOf(section: CleanupSection<T>): CleanupSection<T> =
    CleanupSection(section.kind, section.runs.take(1).map { if (section.kind.grouped) it else it.take(CLEAN_COLUMNS) })

/**
 * Every tile of [sections] once, in screen order. An original that's also one of several similar shots shows in both
 * sections; this has it at its first place.
 */
fun <T> tilesOf(sections: List<CleanupSection<T>>, idOf: (T) -> Long): List<T> = sections.flatMap { it.runs.flatten() }.distinctBy(idOf)

/**
 * What the viewer swipes through from [opened], a tile here: the suggested ones among [tiles] ([offered] ids), in grid
 * order. A tile that stays (an original, a best shot) opens on its own.
 */
fun <T> viewerTiles(tiles: List<T>, offered: Set<Long>, opened: T, idOf: (T) -> Long): List<T> =
    if (idOf(opened) in offered) tiles.filter { idOf(it) in offered } else listOf(opened)

/**
 * Clean up, pushed over the tabs: chips with counts across the top (All, then a chip per category), and under them either
 * All (how much the suggestions free and a preview of each category, "See all" opening it) or one category: a line on
 * why it's suggested, then its photos in a 4-column grid, copies and similar shots in sets around the one that stays
 * (badged Original or Best shot, never suggested). A tap opens the photo, and the viewer swipes through only this view's
 * suggested tiles ([report] gets them, as album pages report theirs); the check in a tile's corner, or a press and hold,
 * selects it, and once something is selected a tap selects too. A chip
 * only filters: nothing starts selected, and the dock's Select all suggested picks what this view suggests; WhatsApp and
 * Big videos suggest nothing, so the user picks. The SelectionBar keeps the picked photos (Clean up stops suggesting
 * them, with Undo) or, after a confirmation saying what it frees, moves them to Trash in one request, with Undo; a
 * copy's original that stays takes over its favourite and collections first. [category] (a [CleanupKind] name) opens on
 * that category; otherwise on All. [onStorage] opens Storage from the top bar.
 */
@Composable
fun CleanupScreen(
    media: List<Media>,
    onBack: () -> Unit,
    onOpen: (Media) -> Unit,
    category: String? = null,
    onStorage: (() -> Unit)? = null,
    report: (List<Media>) -> Unit = {},
) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val context = LocalContext.current
    val result by Cleanup.result.collectAsState()
    val byId = remember(media) { media.associateBy { it.id } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    // The chip showing, a kind's name or ALL, so it survives the activity being recreated. A name no kind has any more (a
    // saved route from before a rename) opens on All rather than on nothing.
    var chip by rememberSaveable { mutableStateOf(category?.takeIf { name -> CleanupKind.entries.any { it.name == name } } ?: ALL) }
    val kind = CleanupKind.entries.find { it.name == chip }

    // Every category's photos, and what each offers to remove: its suggestions, or for WhatsApp and Big videos everything
    val every = remember(result, byId) { result?.let { r -> cleanupSections(r, CleanupKind.entries, byId::get) }.orEmpty() }
    val removable = remember(result, every) {
        val suggests = result?.let { r -> CleanupKind.entries.filter { it.suggested }.associateWith { r.candidates(it).toHashSet() } }.orEmpty()
        every.associate { s -> s.kind to s.runs.flatten().filter { m -> !s.kind.suggested || suggests[s.kind]?.contains(m.id) == true } }
    }
    val suggested = remember(removable) { CleanupKind.entries.filter { it.suggested }.flatMap { removable[it].orEmpty() }.distinctBy { it.id } }
    // What's on screen, which the viewer swipes through, and what Select all and the dock offer here
    val sections = remember(every, kind) { if (kind == null) every.map(::previewOf) else every.filter { it.kind == kind } }
    val tiles = remember(sections) { tilesOf(sections) { it.id } }
    val offered = if (kind == null) suggested else removable[kind].orEmpty()
    val offeredIds = remember(offered) { offered.mapTo(HashSet()) { it.id } }
    // The viewer pages through this view's suggestions. Reported again on a tap, before the viewer opens, so a tile that
    // stays (an original) opens on its own.
    DisposableEffect(tiles, offeredIds) {
        report(tiles.filter { it.id in offeredIds })
        onDispose {}
    }
    val suggestedHere = if (kind == null || kind.suggested) offered else emptyList()

    // Picking. Ids, so it survives the activity being recreated. Only what this view shows or offers counts: photos gone
    // meanwhile drop out
    var selected by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    val picked = remember(selected, tiles, offered) {
        val ids = selected.toHashSet()
        (tiles + offered).distinctBy { it.id }.filter { it.id in ids }
    }
    val pickedIds = remember(picked) { picked.mapTo(HashSet()) { it.id } }
    val selecting = picked.isNotEmpty()
    fun clearSelection() {
        selected = emptyList()
    }
    fun pick(items: List<Media>, on: Boolean) {
        val ids = items.mapTo(HashSet()) { it.id }
        selected = if (on) (selected + ids).distinct() else selected - ids
    }
    // A chip shows another view from its top, with nothing picked
    val list = rememberLazyListState()
    fun choose(name: String) {
        chip = name
        clearSelection()
        scope.launch { list.scrollToItem(0) }
    }

    // One trip to Trash, while Android asks about favourites and then about Trash: every item going, and the copies among
    // them with each one's original, in the same order. Ids, so a step in between survives the activity being recreated.
    var confirming by rememberSaveable { mutableStateOf(false) }
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

    /** Clean up stops suggesting the picked photos, with Undo. */
    fun keep() {
        val ids = picked.map { it.id }
        Cleanup.keep(ids)
        clearSelection()
        scope.launch {
            val undo = snackbar.showSnackbar("Kept ${itemCount(ids.size)}", "Undo", duration = SnackbarDuration.Long)
            if (undo == SnackbarResult.ActionPerformed) Cleanup.keep(ids, keep = false)
        }
    }

    LaunchedEffect(selecting) { if (!selecting) confirming = false } // nothing left to confirm
    val back = { if (selecting) clearSelection() else onBack() }
    BackHandler(onBack = back)

    val subtitle = when {
        result == null -> "Looking through your photos"
        suggested.isNotEmpty() -> "${size(suggested.sumOf { it.size })} you can free"
        else -> "Nothing to suggest"
    }
    val allPicked = offered.isNotEmpty() && offered.all { it.id in pickedIds }
    val docked = result != null && !selecting && suggestedHere.isNotEmpty()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The dock and the SelectionBar swap at once: the one going vanishes, the one coming rises
    val rise = with(LocalDensity.current) { 28.dp.roundToPx() }
    val enter = if (still) EnterTransition.None else slideInVertically(tween(300, easing = RiseEasing)) { rise } + fadeIn(tween(300))

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.statusBarsPadding()) {
                CollapsedTopBar("Clean up", subtitle = subtitle, onBack = back) {
                    onStorage?.let { AlbumIconButton(Sym.Storage, "Storage", it) }
                }
            }
            val r = result
            if (r == null) {
                EmptyState(
                    Sym.CleaningServices, "Looking through your photos",
                    "Copies, similar shots and blurry photos show up here as they're found. Nothing leaves this phone.",
                )
            } else {
                CleanupList(
                    r, kind, sections, removable, suggested, pickedIds, list,
                    onChip = { name ->
                        haptics.tick()
                        choose(name)
                    },
                    onToggle = { m -> pick(listOf(m), m.id !in pickedIds) },
                    onOpen = { m ->
                        if (selecting) {
                            pick(listOf(m), m.id !in pickedIds) // once something is picked, a tap picks, as everywhere in Album
                        } else {
                            report(viewerTiles(tiles, offeredIds, m) { it.id })
                            onOpen(m)
                        }
                    },
                    onHold = { m ->
                        haptics.longPress()
                        pick(listOf(m), m.id !in pickedIds)
                    },
                    bottomPadding = bottom + when {
                        selecting -> SelectionClearance
                        docked -> DockClearance
                        else -> Space.s8
                    },
                    size = ::size,
                )
            }
        }
        // The one primary action while nothing is selected: select what this view suggests, to look over and clean
        AnimatedVisibility(
            docked,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = Space.s4, end = Space.s4, bottom = 20.dp),
            enter = enter,
            exit = ExitTransition.None,
        ) {
            AlbumButton(
                "Select all suggested · ${"%,d".format(suggestedHere.size)}", { pick(suggestedHere, true) },
                Modifier.fillMaxWidth().heightIn(min = 56.dp).shadow(10.dp, CircleShape), ButtonVariant.Primary, Sym.DoneAll,
            )
        }
        AnimatedVisibility(
            selecting,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.s4),
            enter = enter,
            exit = ExitTransition.None,
        ) {
            SelectionBar(
                picked.size,
                listOf(
                    SelAction(Sym.Check, "Keep") { if (picked.isNotEmpty()) keep() },
                    SelAction(Sym.Delete, "Move to Trash", danger = true) {
                        // Without Media management access Android asks itself, and once is enough
                        if (picked.isNotEmpty() && context.canSend(picked.size, MAX_REQUEST, "delete")) {
                            if (MediaStore.canManageMedia(context)) confirming = true else send(picked)
                        }
                    },
                ),
                onClose = ::clearSelection,
                // What this view offers: never an original or a best shot
                onSelectAll = if (offered.isEmpty()) null else ({ pick(if (allPicked) picked else offered, !allPicked) }),
                allLabel = when {
                    allPicked -> "Clear all"
                    kind == null || kind.suggested -> "Select suggested"
                    else -> "Select all"
                },
            )
        }
        AlbumSnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(
                bottom = Space.s4 + when {
                    selecting -> SelectionBarHeight + Space.s2
                    docked -> 56.dp + Space.s2
                    else -> 0.dp
                },
            ),
        )
    }
    if (confirming && picked.isNotEmpty()) {
        ConfirmBar(
            title = "Move ${these(picked.size, picked.first().isVideo)} to Trash?",
            body = "Frees ${size(picked.sumOf { it.size })}. You can restore ${if (picked.size == 1) "it" else "them"} for 30 days.",
            confirmLabel = "Move to Trash",
            onConfirm = {
                confirming = false
                CrashLog.note("cleanup trash ${picked.size}")
                send(picked)
            },
            onCancel = { confirming = false },
            thumbs = picked.take(2),
            fromBar = true,
        )
    }
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

// Rises with a slight overshoot, as the design's docks do
private val RiseEasing = CubicBezierEasing(.2f, .9f, .3f, 1.1f)

/** Why a photo is in [kind], in one line under the chips. */
private fun reasonOf(kind: CleanupKind) = when (kind) {
    CleanupKind.Copies -> "The same photo saved more than once, a smaller or shared copy, or a screenshot of it. The original stays."
    CleanupKind.Similar -> "Shots taken moments apart. Album keeps the best one."
    CleanupKind.Exposure -> "Almost all black or all white."
    CleanupKind.Blurry -> "Out of focus or shaken."
    CleanupKind.Screenshots -> "Screenshots older than 30 days."
    CleanupKind.BigVideos -> "Videos over 100 MB, biggest first. Nothing is suggested: pick what to remove."
    CleanupKind.WhatsApp -> "Photos and videos from WhatsApp chats. Nothing is suggested: pick what to remove."
}

/**
 * Everything under the top bar: the chips, then All (the figure to free and each category's preview) or one category
 * ([kind]: why, then its sets or grid), and the hint at the end. [removable] gives each category's count; [onChip] gets
 * ALL or a kind's name.
 */
@Composable
private fun CleanupList(
    result: CleanupResult,
    kind: CleanupKind?,
    sections: List<CleanupSection<Media>>,
    removable: Map<CleanupKind, List<Media>>,
    suggested: List<Media>,
    picked: Set<Long>,
    state: LazyListState,
    onChip: (String) -> Unit,
    onToggle: (Media) -> Unit,
    onOpen: (Media) -> Unit,
    onHold: (Media) -> Unit,
    bottomPadding: Dp,
    size: (Long) -> String,
) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    // Chips for categories with something in them, and the chosen one, so it never vanishes from under the finger
    val chips = CleanupKind.entries.filter { removable[it].orEmpty().isNotEmpty() || it == kind }
    val tile = TileActions(result, picked, onToggle, onOpen, onHold, size)
    LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(bottom = bottomPadding)) {
        item(key = "chips") {
            // 20dp from the bar's hairline to the chips and 12dp under them, counting the 6dp of touch area each side
            LazyRow(
                Modifier.selectableGroup(),
                contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = 14.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                item(key = ALL) { CleanChip(ALL, kind == null, { onChip(ALL) }) }
                items(chips, key = { it.name }) { k ->
                    CleanChip(k.label, k == kind, { onChip(k.name) }, removable[k].orEmpty().size) { tint ->
                        KindIcon(k, size = 18.dp, tint = tint)
                    }
                }
            }
        }
        if (kind == null) {
            item(key = "hero") {
                if (suggested.isEmpty()) {
                    HeroNumber("All clean", "Nothing to suggest right now. Album checks again as new photos come in.")
                } else {
                    HeroNumber(size(suggested.sumOf { it.size }), "you can free from ${itemCount(suggested.size)}. Nothing leaves without your OK.")
                }
            }
            for (section in sections) {
                val k = section.kind
                val offers = removable[k].orEmpty()
                item(key = "head:$k") {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "See all") { onChip(k.name) }
                            .padding(horizontal = Space.s4, vertical = Space.s3),
                        horizontalArrangement = Arrangement.spacedBy(Space.s3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KindDisc(k)
                        Column(Modifier.weight(1f)) {
                            Text(k.label, style = type.titleMd, color = c.ink)
                            val pick = if (k.suggested) "" else " · you pick"
                            Text("${"%,d".format(offers.size)} · ${size(offers.sumOf { it.size })}$pick", style = type.caption, color = c.inkMuted)
                        }
                        Text("See all", style = type.label, color = c.flashInk)
                    }
                }
                sectionTiles(section, removable, tile)
                item(key = "end:$k") { Spacer(Modifier.height(Space.s4)) }
            }
        } else {
            item(key = "reason") {
                Text(reasonOf(kind), Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s3), style = type.body, color = c.inkMuted)
            }
            if (sections.isEmpty()) {
                item(key = "none") { EmptyState(Sym.CheckCircle, "Nothing here", "Album checks again as new photos come in.") }
            }
            sections.forEach { sectionTiles(it, removable, tile) }
        }
        if (sections.isNotEmpty()) {
            item(key = "hint") {
                Text(
                    "Tap a photo to look closer. Tap its check, or press and hold, to select it.",
                    Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s3),
                    style = type.caption, color = c.inkMuted,
                )
            }
        }
    }
}

/** What every tile needs: the result (for its badge), what's picked, and what its check, a tap and a press and hold do. */
private class TileActions(
    val result: CleanupResult,
    val picked: Set<Long>,
    val onToggle: (Media) -> Unit,
    val onOpen: (Media) -> Unit,
    val onHold: (Media) -> Unit,
    val size: (Long) -> String,
)

/**
 * [section]'s tiles in rows of four. A set (copies, similar shots) starts with a line saying what it is ("Saved 3 times"
 * when every other one is an exact copy) and what it frees, then the one that stays, badged; the others follow, with
 * room under the set. Keys carry the kind: an original that's also a similar shot is a tile in both sections (picked in
 * both, since picking goes by photo).
 */
private fun LazyListScope.sectionTiles(section: CleanupSection<Media>, removable: Map<CleanupKind, List<Media>>, tile: TileActions) {
    val kind = section.kind
    val offers = removable[kind].orEmpty().mapTo(HashSet()) { it.id }
    val keeps = tile.result.groups(kind).mapTo(HashSet()) { it.keep }
    val dark = if (kind == CleanupKind.Exposure) tile.result.dark.toHashSet() else emptySet()
    for (run in section.runs) {
        if (kind.grouped) {
            item(key = "set:$kind:${run.first().id}") {
                val c = AlbumTheme.colors
                val head = when {
                    kind == CleanupKind.Similar -> "${run.size} similar shots"
                    run.drop(1).all { it.id in tile.result.exact } -> "Saved ${run.size} times"
                    else -> "${run.size} versions"
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, bottom = Space.s2),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(head, style = AlbumTheme.type.label, color = c.ink)
                    Text("frees ${tile.size(run.filter { it.id in offers }.sumOf { it.size })}", style = AlbumTheme.type.meta, color = c.inkMuted)
                }
            }
        }
        items(run.chunked(CLEAN_COLUMNS), key = { "$kind:${it.first().id}" }) { row ->
            CleanGridRow(row) { m ->
                val stays = when {
                    kind == CleanupKind.Copies && m.id in keeps -> "Original"
                    kind == CleanupKind.Similar && m.id in keeps -> "Best shot"
                    kind == CleanupKind.Similar && m.id in tile.result.originals -> "Original" // has copies: shared or saved, so it stays too
                    else -> null
                }
                val trait = when {
                    kind == CleanupKind.Exposure -> if (m.id in dark) "Too dark" else "Too bright"
                    m.isVideo -> tile.size(m.size)
                    else -> traitBadge(m)
                }
                CleanTile(m, m.id in tile.picked, trait, stays, { tile.onToggle(m) }, { tile.onOpen(m) }, { tile.onHold(m) })
            }
        }
        if (kind.grouped) item(key = "end:$kind:${run.first().id}") { Spacer(Modifier.height(Space.s4)) }
    }
}

/** A row of Clean up's 4-column grid: [CLEAN_COLUMNS] square slots with the look's gutter between and 16dp at the sides. */
@Composable
fun CleanGridRow(row: List<Media>, tile: @Composable (Media) -> Unit) {
    val gutter = AlbumTheme.spec.gutter
    Row(
        Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, bottom = gutter),
        horizontalArrangement = Arrangement.spacedBy(gutter),
    ) {
        row.forEach { m -> Box(Modifier.weight(1f)) { tile(m) } }
        repeat(CLEAN_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * One photo in Clean up, always showing its check: a tap ([onClick]) opens it, or selects it while selecting; the check
 * (a 48dp target in its corner, [onToggle]) and a press and hold ([onHold]) select it. [trait] is a badge at the top
 * ("Too dark", a video's size); [stays] marks the one that stays in its set ("Original", "Best shot") at the bottom.
 */
@Composable
private fun CleanTile(m: Media, selected: Boolean, trait: String?, stays: String?, onToggle: () -> Unit, onClick: () -> Unit, onHold: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).combinedClickable(
            onClickLabel = "Open",
            onLongClickLabel = if (selected) "Unselect" else "Select",
            onLongClick = onHold,
            hapticFeedbackEnabled = false, // Haptics follows Album's own haptics switch
            onClick = onClick,
        ),
    ) {
        MediaTile(m, selecting = true, selected, Modifier.fillMaxSize(), badge = trait, checkSize = checkSizeFor(CLEAN_COLUMNS))
        stays?.let { Badge(it, Modifier.align(Alignment.BottomStart).padding(Space.s1), BadgeTone.Photo) }
        // Over the check: selects without opening
        Box(
            Modifier.align(Alignment.TopStart).size(Space.target)
                .toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle() }),
        )
    }
}

/**
 * A chip in Clean up's row: one of several, like a tab, so the chosen one fills with ink. [icon] gets the colour to draw
 * in; [count] follows the label in mono.
 */
@Composable
private fun CleanChip(label: String, on: Boolean, onClick: () -> Unit, count: Int? = null, icon: (@Composable (Color) -> Unit)? = null) {
    val c = AlbumTheme.colors
    val ink by animateColorAsState(if (on) c.canvas else c.ink, if (AlbumTheme.spec.still) snap() else tween(160), label = "chipInk")
    val fill by animateColorAsState(if (on) c.ink else Color.Transparent, if (AlbumTheme.spec.still) snap() else tween(160), label = "chipFill")
    Row(
        Modifier.minimumInteractiveComponentSize() // 36dp to see, 48dp to touch
            .heightIn(min = 36.dp)
            .background(fill, CircleShape)
            .border(1.dp, if (on) c.ink else c.line, CircleShape)
            .clip(CircleShape)
            .selectable(on, role = Role.Tab, onClick = onClick)
            .padding(horizontal = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke(ink)
        Text(label, style = AlbumTheme.type.label, color = ink, maxLines = 1)
        count?.let { Text("%,d".format(it), style = AlbumTheme.type.meta, color = ink.copy(alpha = 0.7f)) }
    }
}
