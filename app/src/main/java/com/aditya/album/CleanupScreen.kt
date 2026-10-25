package com.aditya.album

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One card of swipe-to-clean: the item and why it's suggested. [best] marks a similar group's best shot, pre-marked Keep. */
data class ReviewItem(val id: Long, val reasons: List<String>, val best: Boolean = false)

/**
 * The swipe-to-clean order for [kinds]: categories in chip order, each similar group best shot first, every item once with
 * all its reasons ("Similar · 4", "Blurry"). Duplicates show only the copies, since the original is the same file. At most
 * [MAX_REQUEST] cards, so the one Trash request at the end always fits.
 */
fun reviewQueue(result: CleanupResult, kinds: Collection<CleanupKind>): List<ReviewItem> {
    val reasons = LinkedHashMap<Long, MutableList<String>>()
    val best = HashSet<Long>()
    fun add(id: Long, reason: String) {
        reasons.getOrPut(id) { ArrayList() } += reason
    }
    for (kind in CleanupKind.entries.filter { it in kinds }) {
        when (kind) {
            CleanupKind.Similar -> result.similar.forEach { group ->
                best += group.keep
                group.ids.forEach { add(it, "${kind.reason} · ${group.ids.size}") }
            }
            else -> result.candidates(kind).forEach { add(it, kind.reason) }
        }
    }
    return reasons.entries.take(MAX_REQUEST).map { (id, why) -> ReviewItem(id, why, id in best) }
}

// Past this share of the card's width a swipe counts: the stamp is fully shown, the haptic fires, letting go decides
private const val DECIDE_AT = 1 / 3f
private const val TILT_DEGREES = 8f

/**
 * Clean up, pushed over the tabs: a chip per category with its count, and the chosen categories' photos in a grid, where
 * a group's best shot carries a Keep badge. "Start swipe to clean" goes through them one card at a time. Nothing is deleted
 * while swiping: on leaving, every card swiped to Trash goes in one Trash request, with one Snackbar and Undo.
 * [category] (a [CleanupKind] name, as Storage passes) opens on that category alone; otherwise on the suggestions.
 */
@Composable
fun CleanupScreen(media: List<Media>, onBack: () -> Unit, onOpen: (Media) -> Unit, category: String? = null) {
    val c = AlbumTheme.colors
    val context = LocalContext.current
    val result by Cleanup.result.collectAsState()
    val summary by Cleanup.summary.collectAsState()
    val byId = remember(media) { media.associateBy { it.id } }
    val snackbar = remember { SnackbarHostState() }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    // Kind names, so the choice survives the activity being recreated
    var chosen by rememberSaveable { mutableStateOf(category?.let(::listOf) ?: CleanupKind.entries.filter { it.suggested }.map { it.name }) }
    val kinds = CleanupKind.entries.filter { it.name in chosen }
    val queue = remember(result, chosen, byId) { result?.let { reviewQueue(it, kinds) }.orEmpty().filter { it.id in byId } }
    // A swipe session: its cards, fixed when it starts so photos arriving meanwhile can't shift them, and one decision
    // (true = keep) per card swiped so far. Ids and booleans, so it survives the activity being recreated.
    var session by rememberSaveable { mutableStateOf<List<Long>?>(null) }
    var keeps by rememberSaveable { mutableStateOf(emptyList<Boolean>()) }
    val why = remember(queue) { queue.associateBy { it.id } }
    fun endSession() {
        session = null
        keeps = emptyList()
    }
    // The review ends once Android has moved the photos: declining its pop-up keeps every decision to try again
    val trash = rememberTrashWithUndo(snackbar) { endSession() }
    var asked by remember { mutableStateOf(false) } // a second Back after a declined pop-up leaves without asking again

    fun finish() {
        val ids = session ?: return
        val out = ids.filterIndexed { i, _ -> keeps.getOrNull(i) == false }.mapNotNull(byId::get)
        if (out.isEmpty() || asked) {
            endSession()
            asked = false
            return
        }
        asked = true
        CrashLog.note("cleanup trash ${out.size}")
        trash(out)
    }
    val leave = { if (session != null) finish() else onBack() }
    BackHandler(onBack = leave)

    val ids = session
    val suggested = queue.filterNot { it.best }
    val subtitle = when {
        ids != null -> (ids.size - keeps.size).let { if (it > 0) "$it to review" else "All reviewed" }
        result == null -> "Looking through your photos"
        else -> "${itemCount(suggested.size)} · frees ${size(suggested.sumOf { byId[it.id]?.size ?: 0L })}"
    }
    val showStart = ids == null && queue.isNotEmpty()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.statusBarsPadding()) { CollapsedTopBar("Clean up", subtitle = subtitle, onBack = leave) }
            val r = result
            when {
                ids != null -> SwipeSession(
                    ids, keeps, byId, why,
                    onDecide = { keeps = keeps + it },
                    onUndo = {
                        // Steps back over cards whose photo has gone meanwhile, or Undo would land on one and skip forward again
                        var k = keeps.dropLast(1)
                        while (k.isNotEmpty() && byId[ids[k.size]] == null) k = k.dropLast(1)
                        keeps = k
                    },
                    onOpen = onOpen,
                    onFinish = ::finish,
                )
                r == null -> EmptyState(
                    Sym.CleaningServices, "Looking through your photos",
                    "Duplicates, similar shots and blurry photos show up here as they're found. Nothing leaves this phone.",
                )
                else -> CleanupGrid(
                    r, summary, kinds, byId,
                    onToggle = { kind -> chosen = if (kind.name in chosen) chosen - kind.name else chosen + kind.name },
                    onOpen = onOpen,
                    bottomPadding = bottom + if (showStart) Space.target + Space.s8 else Space.s8,
                    size = ::size,
                )
            }
        }
        if (showStart) {
            AlbumButton(
                "Start swipe to clean",
                onClick = {
                    session = queue.map { it.id }
                    keeps = emptyList()
                    asked = false
                },
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = Space.s4, vertical = Space.s4)
                    .fillMaxWidth().floating(),
                ButtonVariant.Primary,
                Sym.Swipe,
            )
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = Space.s4 + if (showStart) Space.target + Space.s3 else 0.dp),
        ) { AlbumSnackbar(it) }
    }
}

/** The chips and, under them, each chosen category's photos: a header with what it would free, then its tiles. */
@Composable
private fun CleanupGrid(
    result: CleanupResult,
    summary: CleanupSummary?,
    kinds: List<CleanupKind>,
    byId: Map<Long, Media>,
    onToggle: (CleanupKind) -> Unit,
    onOpen: (Media) -> Unit,
    bottomPadding: Dp,
    size: (Long) -> String,
) {
    val spec = AlbumTheme.spec
    val counts = summary?.kinds.orEmpty()
    // Chips for categories with something in them, and any chosen one, so it can be unchosen
    val chips = CleanupKind.entries.filter { (counts[it]?.count ?: 0) > 0 || it in kinds }
    if (chips.isEmpty()) {
        EmptyState(Sym.CheckCircle, "Nothing to clean up", "No duplicates, similar shots, blurry photos or old screenshots right now.")
        return
    }
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
                    Chip(kind.label, { onToggle(kind) }, selected = kind in kinds, count = "%,d".format(counts[kind]?.count ?: 0))
                }
            }
        }
        val shown = kinds.filter { (counts[it]?.count ?: 0) > 0 }
        if (shown.isEmpty()) {
            item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(Sym.CheckCircle, "Nothing here", "Choose a category above.")
            }
        }
        for (kind in shown) {
            item(key = "head:$kind", span = { GridItemSpan(maxLineSpan) }) {
                val n = counts[kind]
                Column(Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = Space.s6, bottom = Space.s1)) {
                    Text(kind.label, style = AlbumTheme.type.titleMd, color = AlbumTheme.colors.ink)
                    if (n != null) Text("${itemCount(n.count)} · frees ${size(n.bytes)}", style = AlbumTheme.type.caption, color = AlbumTheme.colors.inkMuted)
                }
            }
            when (kind) {
                CleanupKind.Similar, CleanupKind.Duplicates -> {
                    val groups = if (kind == CleanupKind.Similar) result.similar else result.duplicates
                    groups.forEach { group ->
                        val tiles = group.ids.mapNotNull(byId::get).takeIf { it.size > 1 } ?: return@forEach
                        // Each group starts on a row of its own
                        item(key = "gap:$kind:${group.keep}", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(Space.s2)) }
                        cleanupTiles(kind, tiles, onOpen) { m -> if (m.id == group.keep) "Keep" to BadgeTone.Flash else null }
                    }
                }
                CleanupKind.BigVideos -> cleanupTiles(kind, result.bigVideos.mapNotNull(byId::get), onOpen) { m -> size(m.size) to BadgeTone.Photo }
                else -> cleanupTiles(kind, result.candidates(kind).mapNotNull(byId::get), onOpen) { null }
            }
        }
    }
}

// Keys carry the kind: a blurry similar shot is a tile in both sections
private fun LazyGridScope.cleanupTiles(kind: CleanupKind, tiles: List<Media>, onOpen: (Media) -> Unit, badge: (Media) -> Pair<String, BadgeTone>?) {
    items(tiles, key = { "$kind:${it.id}" }) { m ->
        val b = badge(m)
        MediaTile(
            m, selecting = false, selected = false,
            Modifier.aspectRatio(1f).clickable(onClickLabel = "Open") { onOpen(m) },
            badge = b?.first,
            badgeTone = b?.second ?: BadgeTone.Photo,
        )
    }
}

/** The cards one at a time, then a summary that sends the batch to Trash. A card whose photo has gone meanwhile is skipped. */
@Composable
private fun SwipeSession(
    ids: List<Long>,
    keeps: List<Boolean>,
    byId: Map<Long, Media>,
    why: Map<Long, ReviewItem>,
    onDecide: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onOpen: (Media) -> Unit,
    onFinish: () -> Unit,
) {
    val index = keeps.size
    if (index >= ids.size) {
        val trashed = keeps.count { !it }
        val kept = keeps.size - trashed
        EmptyState(
            Sym.CheckCircle, "All done",
            if (trashed > 0) "Kept $kept · $trashed will move to Trash" else "Kept ${itemCount(kept)}",
            action = { AlbumButton(if (trashed > 0) "Move ${itemCount(trashed)} to Trash" else "Done", onFinish, variant = ButtonVariant.Primary) },
        )
        return
    }
    val id = ids[index]
    val m = byId[id]
    if (m == null) {
        LaunchedEffect(id) { onDecide(true) } // deleted elsewhere: nothing to decide, and keeping is the safe answer
        return
    }
    key(id) { // a fresh card, at rest, for each item
        ReviewCard(
            m, index + 1, ids.size, why[id]?.reasons.orEmpty(),
            onKeep = { onDecide(true) },
            onTrash = { onDecide(false) },
            onUndo = if (index > 0) onUndo else null,
            Modifier.fillMaxSize().navigationBarsPadding().padding(start = Space.s6, end = Space.s6, top = Space.s4, bottom = Space.s6),
            best = why[id]?.best == true,
            onOpen = { onOpen(m) },
        )
    }
}

/**
 * Swipe-to-clean's card (the design system's ReviewCard): the photo follows the finger and tilts; past a third of the width
 * a Keep or Trash stamp shows and a GESTURE_THRESHOLD_ACTIVATE haptic fires, and letting go there decides. The buttons below
 * do the same for people who'd rather tap; Undo ([onUndo], null on the first card) steps back one card. [reasons] show as
 * Badges; [best] adds "Best shot", the card pre-marked Keep. Tapping the photo calls [onOpen].
 */
@Composable
fun ReviewCard(
    media: Media,
    index: Int,
    total: Int,
    reasons: List<String>,
    onKeep: () -> Unit,
    onTrash: () -> Unit,
    onUndo: (() -> Unit)?,
    modifier: Modifier = Modifier,
    best: Boolean = false,
    onOpen: (() -> Unit)? = null,
) {
    val spec = AlbumTheme.spec
    val c = spec.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    var deciding by remember { mutableStateOf(false) }
    // The drag handler is set up once; these keep it calling the latest callbacks
    val keep by rememberUpdatedState(onKeep)
    val toTrash by rememberUpdatedState(onTrash)
    val still by rememberUpdatedState(spec.still)

    fun decide(keeping: Boolean) {
        if (deciding) return // a second tap while the card flies off would decide the next one too
        deciding = true
        scope.launch {
            // Off the side it was swiped to; with Remove animations it just goes
            if (!still) offset.animateTo(if (keeping) width * 1.5f else -width * 1.5f, tween(220, easing = FastOutLinearInEasing))
            if (keeping) keep() else toTrash()
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s4)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                if (best) Badge("Best shot", tone = BadgeTone.Soft, icon = Sym.Star)
                reasons.forEach { Badge(it) }
            }
            Text("$index / $total", style = AlbumTheme.type.meta, color = c.inkMuted)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(Space.radiusLg)
            Box(
                Modifier.aspectRatio(4f / 5f, matchHeightConstraintsFirst = true)
                    .onSizeChanged { width = it.width.coerceAtLeast(1) }
                    .graphicsLayer { // read here, in the draw phase, so dragging never recomposes
                        translationX = offset.value
                        rotationZ = (offset.value / width).coerceIn(-1f, 1f) * TILT_DEGREES
                    }
                    .shadow(10.dp, shape)
                    .clip(shape)
                    .background(c.surfaceHigh)
                    .pointerInput(Unit) {
                        var x = 0f
                        var past = false
                        val settle = {
                            scope.launch { if (still) offset.snapTo(0f) else offset.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
                        }
                        detectHorizontalDragGestures(
                            onDragStart = {
                                x = offset.value
                                past = false
                            },
                            onDragEnd = { if (abs(x) > size.width * DECIDE_AT) decide(x > 0) else settle() },
                            onDragCancel = { settle() },
                        ) { change, dx ->
                            if (deciding) return@detectHorizontalDragGestures
                            change.consume()
                            x += dx
                            scope.launch { offset.snapTo(x) }
                            val over = abs(x) > size.width * DECIDE_AT
                            if (over && !past) haptics.threshold()
                            past = over
                        }
                    }
                    .clickable(enabled = onOpen != null, onClickLabel = "Open", role = Role.Image) { onOpen?.invoke() }
                    .semantics { contentDescription = tileLabel(media, false) },
            ) {
                // Photos load at the card's size from the original; videos show their thumbnail frame
                AsyncImage(if (media.isVideo) Thumb(media.uri) else media.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Stamp("Keep", c.flash, c.onFlash, Modifier.align(Alignment.TopStart).graphicsLayer { alpha = stampAlpha(offset.value / width) })
                Stamp("Trash", c.danger, c.onDanger, Modifier.align(Alignment.TopEnd).graphicsLayer { alpha = stampAlpha(-offset.value / width) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s8, Alignment.CenterHorizontally), verticalAlignment = Alignment.Top) {
            ReviewButton(Sym.Delete, "Trash", c.dangerSoft, c.danger, big = true) { decide(false) }
            ReviewButton(Sym.Undo, "Undo last", c.surfaceHigh, c.ink, big = false, enabled = onUndo != null) { onUndo?.invoke() }
            ReviewButton(Sym.Check, "Keep", c.flash, c.onFlash, big = true) { decide(true) }
        }
    }
}

/** Fades in from a slight drag to fully shown at the point where letting go decides. */
private fun stampAlpha(drag: Float) = ((drag - 0.05f) / (DECIDE_AT - 0.05f)).coerceIn(0f, 1f)

@Composable
private fun Stamp(text: String, bg: Color, fg: Color, modifier: Modifier) {
    Text(
        text,
        modifier.padding(Space.s4).background(bg, CircleShape).padding(horizontal = Space.s4, vertical = Space.s2),
        style = AlbumTheme.type.label,
        color = fg,
    )
}

/** A round button under the card: [big] ones (Trash, Keep) are bar-sized with their label under; Undo is smaller, label for TalkBack only. */
@Composable
private fun ReviewButton(sym: Sym, label: String, bg: Color, fg: Color, big: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !AlbumTheme.spec.still) 0.9f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    Column(
        Modifier.clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (big) Modifier else Modifier.semantics { contentDescription = label })
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        Box(
            Modifier.padding(top = if (big) 0.dp else Space.s2).size(if (big) Space.bar else Space.target)
                .graphicsLayer { scaleX = scale; scaleY = scale }.background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(sym, null, tint = fg) }
        if (big) Text(label, style = AlbumTheme.type.caption, color = AlbumTheme.colors.ink)
    }
}
