package com.aditya.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The docked buttons and the gap under them, so the grid's last row scrolls clear of them
private val DockClearance = 56.dp + 20.dp + Space.s8

/**
 * The look over photos before they leave the Library for ID cards or Private safe. From For you's "Protect N ID photos"
 * ([ids] null) it shows the photos that look like ID cards, less the ones marked Not a document; from the Library's
 * Add-to sheet, the selection ([ids]). All start ticked, and the check on each unticks it; a tap opens it full size
 * ([onOpen]), and the viewer swipes through this page's photos as [report] gives them. Chips choose where they go
 * ([bucket], a SafeItem bucket, at first), and the docked button runs [moveToIdCards] or [moveToSafe] on the ticked ones:
 * ID cards after their screen lock (or PIN), Private safe at once, this page being its confirmation. From For you,
 * unticked ones can be marked Not a document, with Undo, so they aren't suggested again. [media] is the photos to look in.
 */
@Composable
fun ProtectReviewScreen(
    media: List<Media>,
    ids: List<Long>?,
    bucket: Int,
    moveToIdCards: MoveToIdCards,
    moveToSafe: MoveToSafe,
    onBack: () -> Unit,
    onOpen: (Media) -> Unit,
    report: (List<Media>) -> Unit,
) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val suggesting = ids == null
    val screenLock = remember { Safe.hasScreenLock(context) }
    val found = if (suggesting) rememberIdCardIds() else null
    remember { NotDocuments.load(context) }
    val notDocuments by NotDocuments.ids.collectAsState()
    // Null for a moment while the index is read. Photos that leave the Library meanwhile drop out.
    val items = remember(media, ids, found, notDocuments) {
        when {
            ids != null -> ids.toHashSet().let { wanted -> media.filter { it.id in wanted } }
            found == null -> null
            else -> idSuggestions(media, found, notDocuments, Media::id)
        }
    }
    DisposableEffect(items) {
        report(items.orEmpty())
        onDispose {}
    }
    // What's unticked rather than ticked, so all start ticked. Ids, so it survives the activity being recreated.
    var unticked by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var to by rememberSaveable { mutableIntStateOf(bucket) }
    // Whether each place is still empty, so the first move into it says these become the only copy
    val empty by produceState(setOf<Int>()) {
        value = withContext(Dispatchers.IO) { listOf(SafeItem.ID_CARDS, SafeItem.SAFE).filter { Safe.isEmpty(it) }.toSet() }
    }
    val list = items.orEmpty()
    val off = unticked.toHashSet()
    val chosen = list.filter { it.id !in off }
    val left = list.size - chosen.size

    fun tick(m: Media) {
        haptics.tick()
        unticked = if (m.id in off) unticked - m.id else unticked + m.id
    }

    fun notDocument() {
        val gone = list.filter { it.id in off }.mapTo(HashSet()) { it.id }
        CrashLog.note("not documents ${gone.size}")
        NotDocuments.mark(gone)
        unticked = emptyList()
        scope.launch {
            val undo = snackbar.showSnackbar("Won't suggest ${counted(gone.size, "photo", "photos")} again", "Undo")
            if (undo == SnackbarResult.ActionPerformed) {
                NotDocuments.mark(gone, on = false)
                unticked = unticked + gone // back as they were: unticked
            }
        }
    }

    fun move() {
        CrashLog.note("review move ${chosen.size} to ${if (to == SafeItem.ID_CARDS) "id cards" else "safe"}")
        if (to == SafeItem.ID_CARDS) moveToIdCards(chosen) else moveToSafe(chosen, confirmed = true)
    }

    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(c.canvas)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.statusBarsPadding()) {
                CollapsedTopBar(
                    if (suggesting) "Protect ID photos" else "Keep private",
                    subtitle = if (list.isEmpty()) null else "${"%,d".format(chosen.size)} of ${itemCount(list.size)} selected",
                    onBack = onBack,
                )
            }
            when {
                items == null -> {} // a moment, while the index is read
                list.isEmpty() -> EmptyState(
                    Sym.IdCard, "Nothing to look over",
                    if (suggesting) "Aadhaar, PAN or bank card photos show up here once Album has read their text." else "These photos have left the Library.",
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + DockClearance + if (suggesting && left > 0) 56.dp + Space.s2 else 0.dp)) {
                    item(key = "where") {
                        Group(Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s3, bottom = Space.s3)) {
                            ChipRow(
                                "Move to", listOf(SafeItem.ID_CARDS to "ID cards", SafeItem.SAFE to "Private safe"), to, { to = it },
                                sub = (if (to == SafeItem.ID_CARDS) idMoveNote(screenLock) else "They leave the Library and search, and open with your PIN or fingerprint.") +
                                    if (to in empty) " ${onlyCopyLine(chosen.size)}" else "",
                            )
                        }
                    }
                    item(key = "hint") {
                        Text(
                            if (suggesting) "Album found these in your photos' text. Untick any that aren't documents. Tap a photo to read it."
                            else "Untick any that should stay in the Library. Tap a photo to look closer.",
                            Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s3),
                            style = type.caption, color = c.inkMuted,
                        )
                    }
                    items(list.chunked(CLEAN_COLUMNS), key = { it.first().id }) { row ->
                        CleanGridRow(row) { m -> ReviewTile(m, m.id !in off, { tick(m) }, { onOpen(m) }) }
                    }
                }
            }
        }
        // Not a document for what's unticked, over the one primary action: the move
        if (list.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = Space.s4, end = Space.s4, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                if (suggesting && left > 0) {
                    AlbumButton(
                        "Not a document · ${"%,d".format(left)}", ::notDocument,
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).shadow(10.dp, CircleShape), ButtonVariant.Tonal, Sym.VisibilityOff,
                    )
                }
                AlbumButton(
                    "Move ${"%,d".format(chosen.size)} to ${placeOf(to)}", ::move,
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).shadow(10.dp, CircleShape), ButtonVariant.Primary,
                    if (to == SafeItem.ID_CARDS) Sym.IdCard else Sym.Lock, enabled = chosen.isNotEmpty(),
                )
            }
        }
        AlbumSnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = 20.dp + if (list.isEmpty()) 0.dp else (56.dp + Space.s2) * if (suggesting && left > 0) 2 else 1),
        )
    }
}

/**
 * A photo on the review page, always showing its check. The check is a 48dp target of its own that ticks or unticks it
 * ([onTick]); a tap anywhere else opens it full size ([onOpen]), to read it.
 */
@Composable
private fun ReviewTile(m: Media, ticked: Boolean, onTick: () -> Unit, onOpen: () -> Unit) {
    Box(Modifier.aspectRatio(1f).clickable(onClickLabel = "Open", onClick = onOpen)) {
        MediaTile(m, selecting = true, ticked, Modifier.fillMaxSize(), checkSize = checkSizeFor(CLEAN_COLUMNS))
        Box(
            Modifier.align(Alignment.TopStart).size(Space.target)
                .toggleable(ticked, role = Role.Checkbox, onValueChange = { onTick() })
                .semantics { contentDescription = "Move this one" },
        )
    }
}
