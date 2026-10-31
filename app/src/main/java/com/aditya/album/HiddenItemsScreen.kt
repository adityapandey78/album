package com.aditya.album

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What Hidden items says about what hiding does, and where hidden photos really are, so nobody mistakes it for the safe. */
private const val WHAT_HIDING_DOES =
    "Other apps, photo pickers, cloud backup and a computer can still see these. For full privacy, move them to the Private safe."

/**
 * Hidden items, pushed from Settings > Privacy and safe: the people and folder albums behind the privacy PIN. Locked: the
 * PIN pad. Open: People and Albums as cards, each with its check; tap the check (or long-press) to pick, then Unhide, or
 * tap the card to open it ([onOpen] gets its album id); Hide more (top bar) opens a sheet of people and albums to hide.
 * Below them, the folders hidden only from the Library (Hide from Library), each a row that shows it there again.
 * Screenshots are blocked while it's on screen, privacy stays unlocked while it is (and while a hidden album or person
 * opened from it is), and leaving it locks privacy. Draws its own background, insets and Back.
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
            PrivacyGate("Hidden items", Sym.Lock, "Hidden items are locked.", onBack)
        }
    }
}

@Composable
private fun HiddenItems(onBack: () -> Unit, onOpen: (albumId: Long) -> Unit) {
    val context = LocalContext.current
    val everyone by People.albums.collectAsState()
    val people = remember(everyone) { peopleInHiddenItems(everyone) }
    val media = Gallery.media.collectAsState().value.orEmpty()
    val locked by AlbumPrefs.locked.collectAsState()
    val folders = remember(media) { albumsOf(media) }
    val albums = remember(folders, locked) { folders.filter { it.key in locked } }
    val offLibrary by AlbumPrefs.hidden.collectAsState()
    val libraryHidden = remember(folders, offLibrary, locked) { listedHidden(offLibrary, locked).let { keys -> folders.filter { it.key in keys } } }
    var selected by remember { mutableStateOf(emptySet<Long>()) } // album ids: a person's (personAlbumId) or a folder's
    var adding by remember { mutableStateOf(false) }
    // People and albums that left meanwhile (unhidden elsewhere, deleted) leave the selection too
    val chosenPeople = people.filter { it.albumId in selected }
    val chosenAlbums = albums.filter { it.id in selected }
    val chosen = chosenPeople.size + chosenAlbums.size
    val selecting = chosen > 0
    val toggle = { id: Long -> selected = if (id in selected) selected - id else selected + id }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BackHandler { if (selecting) selected = emptySet() else onBack() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.statusBarsPadding()) {
                CollapsedTopBar("Hidden items", subtitle = "Locks when you leave", onBack = onBack) {
                    if (selecting && chosen < people.size + albums.size) {
                        AlbumIconButton(Sym.SelectAll, "Select all", { selected = (people.map { it.albumId } + albums.map { it.id }).toSet() })
                    } else {
                        AlbumIconButton(Sym.Add, "Hide more", { adding = true })
                    }
                    AlbumIconButton(Sym.Lock, "Lock now", {
                        CrashLog.note("hidden lock now")
                        PrivacyLock.lock()
                    })
                }
            }
            LazyVerticalGrid(
                GridCells.Fixed(3),
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Space.s4, end = Space.s4, top = Space.s4,
                    bottom = bottom + Space.s8 + if (selecting) DockClearance else 0.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                fullRow("note") {
                    Group(Modifier.padding(bottom = Space.s3)) {
                        GroupRow("Hidden only in Album", null, icon = Sym.Info, sub = WHAT_HIDING_DOES)
                    }
                }
                fullRow("people head") { SectionHead("People") }
                if (people.isEmpty()) fullRow("people none") { Note("No hidden people.") }
                hiddenCards(people, { it.albumId }, selected, selecting, toggle, onOpen) { p ->
                    HiddenCard(p.name ?: "Unnamed person", "%,d".format(p.items.size), "${p.name ?: "Unnamed person"}, ${counted(p.items.size, "photo", "photos")}", round = true) {
                        FaceImage(p.cover, Modifier.matchParentSize())
                    }
                }
                fullRow("albums head") { SectionHead("Albums") }
                if (albums.isEmpty()) fullRow("albums none") { Note("No hidden albums.") }
                hiddenCards(albums, { it.id }, selected, selecting, toggle, onOpen) { a ->
                    HiddenCard(a.name, "%,d".format(a.count), "${a.name}, ${itemCount(a.count)}") { Thumbnail(a.cover, Modifier.matchParentSize()) }
                }
                fullRow("how") { Note("Tap a circle to select, then Unhide. Tap a card to open it.") }
                if (libraryHidden.isNotEmpty()) {
                    fullRow("library head") { SectionHead("Hidden from the Library") }
                    fullRow("library") { LibraryHiddenGroup(libraryHidden) }
                }
            }
        }
        // The design's page dock: one big Unhide where the thumb is
        AnimatedVisibility(
            selecting,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = Space.s4, end = Space.s4, bottom = 20.dp),
            enter = dockRise(),
            exit = ExitTransition.None,
        ) {
            AlbumButton(
                "Unhide $chosen", {
                    Hidden.unhide(context, chosenPeople, chosenAlbums, counted = true)
                    selected = emptySet()
                },
                Modifier.fillMaxWidth().heightIn(min = 56.dp).floating(), ButtonVariant.Primary, icon = Sym.Visibility,
            )
        }
    }

    if (adding) {
        HideMoreSheet(hideablePeople(everyone), folders.filter { it.key !in locked }, onDismiss = { adding = false }) { p, a ->
            adding = false
            Hidden.hide(context, p, a, counted = true)
        }
    }
}

/**
 * Folders hidden only from the Library (Hide from Library on a folder's sheet): not behind the PIN, still in Collections.
 * Tapping one shows it in the Library again.
 */
@Composable
private fun LibraryHiddenGroup(folders: List<Album>) = Group(
    Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s3),
    note = "They stay in Collections. Tap one to show it in the Library again.",
) {
    folders.forEach { album ->
        GroupRow(album.name, {
            CrashLog.note("show folder in library")
            AlbumPrefs.setHidden(album.key, false)
        }, icon = Sym.Folder, sub = "${itemCount(album.count)} · tap to show in the Library", trailing = Sym.Visibility)
    }
}

/** Room under the grid for the Unhide dock: its height and the space below it. */
private val DockClearance = 56.dp + 20.dp + Space.s4

/** A card for each of [items]: tap opens it, or toggles it while picking; its check or a long-press picks it. */
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
    CardScope(
        checked = id in selected,
        selecting = selecting,
        onClick = {
            if (selecting) toggle(id) else {
                CrashLog.note("open hidden")
                onOpen(id)
            }
        },
        onToggle = { toggle(id) }, // AlbumCard plays the long-press haptic
    ).card(item)
}

/** What a card in Hidden items needs to know about selection; see [hiddenCards]. */
private class CardScope(val checked: Boolean, val selecting: Boolean, val onClick: () -> Unit, val onToggle: () -> Unit)

/**
 * The design's pick card: an AlbumCard (round for a person) with its check always at its top right, a 48dp target that
 * picks it, and a 3dp flash ring inside the cover once picked.
 */
@Composable
private fun CardScope.HiddenCard(name: String, count: String, description: String, round: Boolean = false, cover: @Composable BoxScope.() -> Unit) {
    val ring = AlbumTheme.colors.flash
    Box(Modifier.padding(bottom = Space.s4)) {
        AlbumCard(
            name = name,
            meta = listOf(count),
            description = description + if (selecting) if (checked) ", selected" else ", not selected" else "",
            onClick = onClick,
            round = round,
            onLongClick = onToggle,
        ) {
            cover()
            if (checked) Box(Modifier.matchParentSize().border(3.dp, ring, if (round) CircleShape else RoundedCornerShape(Space.radiusMd)))
        }
        // The check sits 8dp in from the corner, as drawn; its target reaches 4dp past the card's edge
        Box(
            Modifier.align(Alignment.TopEnd).offset(Space.s1, -Space.s1).size(Space.target)
                .toggleable(checked, interactionSource = null, indication = null, role = Role.Checkbox, onValueChange = { onToggle() })
                .semantics { contentDescription = "Select $name" },
            contentAlignment = Alignment.Center,
        ) { SelectCheck(checked) }
    }
}

/**
 * The design's Hide more sheet: People and Albums on a segmented control, a row each to pick (picks are kept across both),
 * then Cancel and Hide. [onHide] gets the people and albums picked.
 */
@Composable
private fun HideMoreSheet(people: List<PersonAlbum>, albums: List<Album>, onDismiss: () -> Unit, onHide: (List<PersonAlbum>, List<Album>) -> Unit) {
    var tab by remember { mutableStateOf(HideTab.People) }
    var picked by remember { mutableStateOf(emptySet<Long>()) } // album ids, as in Hidden items
    val toggle = { id: Long -> picked = if (id in picked) picked - id else picked + id }
    val chosenPeople = people.filter { it.albumId in picked }
    val chosenAlbums = albums.filter { it.id in picked }
    val chosen = chosenPeople.size + chosenAlbums.size
    Sheet(onDismiss, "Hide people or albums") {
        Text(
            "They leave Album's Library, search and memories. Other apps still show their photos.",
            Modifier.padding(bottom = Space.s3), style = AlbumTheme.type.caption, color = AlbumTheme.colors.inkMuted,
        )
        Segmented(listOf(HideTab.People to "People", HideTab.Albums to "Albums"), tab) { tab = it }
        // At most about half the screen, as in the design, so Cancel and Hide stay in reach
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.46f).dp).padding(vertical = Space.s2)) {
            when (tab) {
                HideTab.People -> {
                    if (people.isEmpty()) item { SheetEmpty("Everyone Album found is hidden already, or no one is found yet.") }
                    items(people, key = { it.albumId }) { p ->
                        val photos = counted(p.items.size, "photo", "photos")
                        PickRow(p.name ?: "Unnamed person", if (p.species != null) "Pet · $photos" else photos, p.albumId in picked, { toggle(p.albumId) }, round = true) {
                            FaceImage(p.cover, Modifier.matchParentSize())
                        }
                    }
                }
                HideTab.Albums -> {
                    if (albums.isEmpty()) item { SheetEmpty("Every album is hidden already.") }
                    items(albums, key = { it.id }) { a ->
                        PickRow(a.name, itemCount(a.count), a.id in picked, { toggle(a.id) }) { Thumbnail(a.cover, Modifier.matchParentSize()) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Space.s2), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            AlbumButton("Cancel", onDismiss, Modifier.weight(1f).heightIn(min = 52.dp))
            AlbumButton(
                if (chosen == 0) "Hide" else "Hide $chosen", { onHide(chosenPeople, chosenAlbums) },
                Modifier.weight(1.4f).heightIn(min = 52.dp), ButtonVariant.Primary, enabled = chosen > 0,
            )
        }
    }
}

private enum class HideTab { People, Albums }

/** What a tab of Hide more says when there's nothing left to hide. */
@Composable
private fun SheetEmpty(text: String) = Text(text, Modifier.padding(vertical = Space.s4), style = AlbumTheme.type.body, color = AlbumTheme.colors.inkMuted)

/**
 * The design's segmented control: [options] as 36dp segments on a 42dp surface-high track, the chosen one raised on
 * surface. Each segment takes touches a little past the track, so it's still a 48dp target.
 */
@Composable
internal fun <T> Segmented(options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    val c = AlbumTheme.colors
    Row(
        // The track is drawn 3dp inside the 48dp row, which keeps its 8dp margin above and below
        Modifier.fillMaxWidth().padding(vertical = Space.s2 - 3.dp).height(Space.target)
            .drawBehind {
                val inset = 3.dp.toPx()
                drawRoundRect(c.surfaceHigh, Offset(0f, inset), Size(size.width, size.height - 2 * inset), CornerRadius(size.height / 2))
            }
            .padding(horizontal = 3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (v, label) ->
            val on = v == value
            val ground by animateColorAsState(if (on) c.surface else c.surfaceHigh, tween(160), label = "segment")
            Box(
                Modifier.weight(1f).fillMaxHeight().selectable(on, interactionSource = null, indication = null, role = Role.Tab, onClick = { onChange(v) }),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.fillMaxWidth().height(36.dp).then(if (on) Modifier.shadow(1.dp, CircleShape) else Modifier).clip(CircleShape).background(ground),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = AlbumTheme.type.label, color = if (on) c.ink else c.inkMuted) }
            }
        }
    }
}

/** A picker row: the cover (round for a face), name and count, and the check; the whole row toggles. */
@Composable
internal fun PickRow(label: String, sub: String, checked: Boolean, onToggle: () -> Unit, round: Boolean = false, cover: @Composable BoxScope.() -> Unit) {
    val c = AlbumTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(Space.radiusMd))
            .toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle() }).padding(vertical = Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(Space.target).clip(if (round) CircleShape else RoundedCornerShape(10.dp)).background(c.surfaceHigh),
            contentAlignment = Alignment.Center,
            content = cover,
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = AlbumTheme.type.body.copy(fontWeight = FontWeight.Medium), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = AlbumTheme.type.caption, color = c.inkMuted, maxLines = 1)
        }
        SelectCheck(checked, onSurface = true)
    }
}
