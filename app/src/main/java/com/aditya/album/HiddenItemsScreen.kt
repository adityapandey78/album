package com.aditya.album

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow

/** What Hidden items says about where hidden photos really are, so nobody mistakes hiding for the safe. */
private const val STILL_ON_PHONE =
    "Hidden photos stay where they are on your phone, so other apps still show them. Private safe encrypts photos nobody else should see."

/**
 * Hidden items, pushed from Settings > Privacy or Collections: the people and folder albums behind the privacy PIN.
 * Locked: the PIN pad. Open: People and Albums sections; tap one to open it ([onOpen] gets its album id), Add to hide
 * more, long-press to select and Unhide. Screenshots are blocked while it's on screen, and privacy stays unlocked while
 * it is. Draws its own background, insets and Back.
 */
@Composable
fun HiddenItemsScreen(onBack: () -> Unit, onOpen: (albumId: Long) -> Unit) {
    val context = LocalContext.current
    remember { PrivacyLock.init(context) }
    PrivatePage()
    val open = Safe.key.collectAsState().value != null
    Box(Modifier.fillMaxSize().background(AlbumTheme.colors.canvas).pointerInput(Unit) {}) { // catches taps meant for the screen underneath
        if (open) {
            HiddenItems(onBack, onOpen)
        } else {
            PrivacyGate("Hidden items", Sym.VisibilityOff, "People and albums you hide show only here.", onBack)
        }
    }
}

@Composable
private fun HiddenItems(onBack: () -> Unit, onOpen: (albumId: Long) -> Unit) {
    val context = LocalContext.current
    val spec = AlbumTheme.spec
    val everyone by People.albums.collectAsState()
    val people = remember(everyone) { peopleInHiddenItems(everyone) }
    val media = Gallery.media.collectAsState().value.orEmpty()
    val locked by AlbumPrefs.locked.collectAsState()
    val folders = remember(media) { albumsOf(media) }
    val albums = remember(folders, locked) { folders.filter { it.key in locked } }
    var selected by remember { mutableStateOf(emptySet<Long>()) } // album ids: a person's (personAlbumId) or a folder's
    var adding by remember { mutableStateOf<Adding?>(null) }
    val grid = rememberLazyGridState()
    // People and albums that left meanwhile (unhidden elsewhere, deleted) leave the selection too
    val chosenPeople = people.filter { it.albumId in selected }
    val chosenAlbums = albums.filter { it.id in selected }
    val selecting = chosenPeople.isNotEmpty() || chosenAlbums.isNotEmpty()
    val toggle = { id: Long -> selected = if (id in selected) selected - id else selected + id }
    val subtitle = if (people.isEmpty() && albums.isEmpty()) null else "${counted(people.size, "person", "people")} · ${counted(albums.size, "album", "albums")}"

    Box(Modifier.fillMaxSize()) {
        PushedGridPage("Hidden items", subtitle, grid, onBack, actions = {
            AlbumIconButton(Sym.Lock, "Lock now", {
                CrashLog.note("hidden lock now")
                PrivacyLock.lock()
            })
        }) {
            if (people.isEmpty() && albums.isEmpty()) {
                fullRow("empty") {
                    EmptyState(Sym.VisibilityOff, "Nothing hidden yet", "People and albums you hide show only here, after your PIN or fingerprint.") {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                            AlbumButton("Hide people", { adding = Adding.People })
                            AlbumButton("Hide albums", { adding = Adding.Albums })
                        }
                    }
                }
            } else {
                fullRow("people head") { SectionHead("People", action = "Add", onAction = { adding = Adding.People }) }
                if (people.isEmpty()) fullRow("people none") { Note("No one yet. Hide someone from their page, or tap Add.") }
                hiddenCards(people, { it.albumId }, selected, selecting, toggle, onOpen) { p ->
                    HiddenCard(p.name ?: "Unnamed person", "%,d".format(p.items.size), "${p.name ?: "Unnamed person"}, ${counted(p.items.size, "photo", "photos")}", round = true) {
                        FaceImage(p.cover, Modifier.matchParentSize())
                    }
                }
                fullRow("albums head") { SectionHead("Albums", action = "Add", onAction = { adding = Adding.Albums }) }
                if (albums.isEmpty()) fullRow("albums none") { Note("No albums yet. Hide one from its long-press menu, or tap Add.") }
                hiddenCards(albums, { it.id }, selected, selecting, toggle, onOpen) { a ->
                    HiddenCard(a.name, "%,d".format(a.count), "${a.name}, ${itemCount(a.count)}") { Thumbnail(a.cover, Modifier.matchParentSize()) }
                }
            }
            fullRow("note") { Box(Modifier.padding(top = Space.s6)) { Note(STILL_ON_PHONE) } }
            if (selecting) fullRow("clearance") { Spacer(Modifier.height(SelectionClearance)) }
        }
        BackHandler(selecting) { selected = emptySet() }
        AnimatedVisibility(
            selecting,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.s4),
            enter = if (spec.still) fadeIn(tween(0)) else slideInVertically { it } + fadeIn(),
            exit = if (spec.still) fadeOut(tween(0)) else slideOutVertically { it } + fadeOut(),
        ) {
            val all = people.size + albums.size
            SelectionBar(
                chosenPeople.size + chosenAlbums.size,
                listOf(SelAction(Sym.Visibility, "Unhide") {
                    Hidden.unhide(context, chosenPeople, chosenAlbums)
                    selected = emptySet()
                }),
                onClose = { selected = emptySet() },
                onSelectAll = { selected = (people.map { it.albumId } + albums.map { it.id }).toSet() }.takeIf { chosenPeople.size + chosenAlbums.size < all },
            )
        }
    }

    when (adding) {
        Adding.People -> PickSheet(
            "Hide people", hideablePeople(everyone), { it.albumId }, { counted(it, "person", "people") },
            empty = "Everyone Album found is hidden already, or no one is found yet.",
            onDismiss = { adding = null },
            onPick = {
                adding = null
                PrivacyLock.touch()
                Hidden.hide(context, it, emptyList())
            },
            row = { p, checked, onToggle ->
                PickRow(p.name ?: "Unnamed person", counted(p.items.size, "photo", "photos"), checked, onToggle, round = true) {
                    FaceImage(p.cover, Modifier.matchParentSize())
                }
            },
        )
        Adding.Albums -> PickSheet(
            "Hide albums", folders.filter { it.key !in locked }, { it.id }, { counted(it, "album", "albums") },
            empty = "Every album is hidden already.",
            onDismiss = { adding = null },
            onPick = {
                adding = null
                PrivacyLock.touch()
                Hidden.hide(context, emptyList(), it)
            },
            row = { a, checked, onToggle ->
                PickRow(a.name, itemCount(a.count), checked, onToggle) { Thumbnail(a.cover, Modifier.matchParentSize()) }
            },
        )
        null -> {}
    }
}

private enum class Adding { People, Albums }

/** A card for each of [items]: tap opens it, or toggles it while selecting; long-press starts selecting. */
private fun <T> LazyGridScope.hiddenCards(
    items: List<T>,
    idOf: (T) -> Long,
    selected: Set<Long>,
    selecting: Boolean,
    toggle: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    card: @Composable CardScope.(T) -> Unit,
) = items(items, key = idOf) { item ->
    val id = idOf(item)
    val haptics = rememberHaptics()
    CardScope(
        checked = id in selected,
        selecting = selecting,
        onClick = {
            if (selecting) toggle(id) else {
                CrashLog.note("open hidden")
                onOpen(id)
            }
        },
        onLongClick = {
            haptics.longPress()
            toggle(id)
        },
    ).card(item)
}

/** What a card in Hidden items needs to know about selection; see [hiddenCards]. */
private class CardScope(val checked: Boolean, val selecting: Boolean, val onClick: () -> Unit, val onLongClick: () -> Unit)

/** The design's AlbumCard (round for a person), with the selection check over its cover while selecting. */
@Composable
private fun CardScope.HiddenCard(name: String, count: String, description: String, round: Boolean = false, cover: @Composable BoxScope.() -> Unit) {
    AlbumCard(
        name = name,
        meta = listOf(count),
        description = description + if (selecting) if (checked) ", selected" else ", not selected" else "",
        onClick = onClick,
        modifier = Modifier.padding(bottom = Space.s4),
        round = round,
        onLongClick = onLongClick,
    ) {
        cover()
        // A round cover clips its corners, so a face's check sits at the bottom, inside the circle
        if (selecting) SelectCheck(checked, Modifier.align(if (round) Alignment.BottomCenter else Alignment.TopStart).padding(Space.s2))
    }
}

/**
 * A sheet that picks several of [items] (none at first) to hide: a row each, then a primary button that names how many
 * ([what]: "3 people"). [empty] when there's nothing to pick.
 */
@Composable
private fun <T> PickSheet(
    title: String,
    items: List<T>,
    idOf: (T) -> Long,
    what: (Int) -> String,
    empty: String,
    onDismiss: () -> Unit,
    onPick: (List<T>) -> Unit,
    row: @Composable (T, Boolean, () -> Unit) -> Unit,
) {
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    Sheet(onDismiss, title) {
        if (items.isEmpty()) {
            Text(empty, style = AlbumTheme.type.body, color = AlbumTheme.colors.inkMuted)
            return@Sheet
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(items, key = idOf) { item ->
                val id = idOf(item)
                row(item, id in picked) { picked = if (id in picked) picked - id else picked + id }
            }
        }
        val chosen = items.filter { idOf(it) in picked }
        AlbumButton(
            if (chosen.isEmpty()) "Hide" else "Hide ${what(chosen.size)}",
            { onPick(chosen) },
            Modifier.fillMaxWidth().padding(top = Space.s4).heightIn(min = Space.target + Space.s2),
            ButtonVariant.Primary,
            icon = Sym.Lock,
            enabled = chosen.isNotEmpty(),
        )
    }
}

/** A picker row: the cover (round for a face), name and count, and the check; the whole row toggles. */
@Composable
private fun PickRow(label: String, sub: String, checked: Boolean, onToggle: () -> Unit, round: Boolean = false, cover: @Composable BoxScope.() -> Unit) {
    val c = AlbumTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.target + Space.s2).clip(RoundedCornerShape(Space.radiusMd))
            .toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle() }).padding(Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(Space.target).clip(if (round) CircleShape else RoundedCornerShape(Space.radiusMd)).background(c.surfaceHigh),
            contentAlignment = Alignment.Center,
            content = cover,
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = AlbumTheme.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = AlbumTheme.type.meta, color = c.inkMuted, maxLines = 1)
        }
        SelectCheck(checked, onSurface = true)
    }
}
