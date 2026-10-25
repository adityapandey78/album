package com.aditya.album

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val NO_NAME = "Add a name"
private const val COULDNT_SAVE = "Couldn't save that change to People"

/** People shown in People and Collections, biggest first. With [hidden], the hidden ones instead, however small, so each can be shown again. */
fun shownPeople(all: List<PersonAlbum>, hidden: Boolean = false) =
    all.filter { it.hidden == hidden && (hidden || isShown(it.name, it.faces)) }.sortedWith(compareByDescending<PersonAlbum> { it.items.size }.thenBy { it.cluster })

private fun titleOf(p: PersonAlbum) = p.name ?: "this person"

/** A person page's title: their name, or the prompt to give one. For the shell's collapsed bar; [PersonHeader] shows the same. */
fun personTitle(person: PersonAlbum) = person.name ?: NO_NAME

private fun photoCount(p: PersonAlbum) = counted(p.items.size, "photo", "photos")

fun scanningText(p: IndexProgress) = "Finding people: ${"%,d".format(p.done)} of ${"%,d".format(p.total)} photos checked"

/**
 * The person page open at [albumId], or null when it isn't a person page. Watches People only while one is open.
 * A group that just became a person (or merged) is found under its new id, so its page stays open meanwhile.
 */
@Composable
fun rememberPerson(albumId: Long?): PersonAlbum? {
    val cluster = albumId?.let(::clusterOfAlbum) ?: return null
    val people by People.albums.collectAsState()
    return people.find { it.cluster == cluster } ?: People.movedTo(cluster)?.let { to -> people.find { it.cluster == to } }
}

/**
 * Everyone, pushed over the tabs: a grid of faces, biggest first, then hidden people at the end.
 * [state] belongs to the caller, so the scroll position survives a visit to a person's page.
 */
@Composable
fun PeopleScreen(state: LazyGridState, onBack: () -> Unit, onOpenPerson: (albumId: Long) -> Unit) {
    val all by People.albums.collectAsState()
    val people = remember(all) { shownPeople(all) }
    val hidden = remember(all) { shownPeople(all, hidden = true) }
    val progress by People.progress.collectAsState()
    val subtitle = counted(people.size, "person", "people") + if (hidden.isEmpty()) "" else " · ${"%,d".format(hidden.size)} hidden"
    PushedGridPage("People", subtitle, state, onBack) {
        if (progress.total > 0) fullRow("scan") { Note(scanningText(progress)) }
        if (people.isEmpty() && hidden.isEmpty()) fullRow("empty") {
            EmptyState(Sym.Face, "No people yet", "Album looks for faces in your photos on this phone, a little at a time in the background.")
        }
        items(people, key = { it.cluster }) { p -> PersonCard(p, Modifier.padding(bottom = Space.s4)) { onOpenPerson(p.albumId) } }
        if (hidden.isNotEmpty()) {
            fullRow("hidden") { SectionHead("Hidden people") }
            // Dimmed, but the heading says why: the state never rests on the tint alone
            items(hidden, key = { it.cluster }) { p -> PersonCard(p, Modifier.padding(bottom = Space.s4).alpha(0.6f)) { onOpenPerson(p.albumId) } }
        }
    }
}

/** A person as a round AlbumCard: their best face, name (or "Add a name") and photo count, centred. */
@Composable
fun PersonCard(p: PersonAlbum, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AlbumCard(
        name = p.name ?: NO_NAME,
        meta = listOf("%,d".format(p.items.size)),
        description = "${p.name ?: "Unnamed person"}, ${photoCount(p)}",
        onClick = onClick,
        modifier = modifier,
        round = true,
        nameColor = if (p.name == null) AlbumTheme.colors.flashInk else AlbumTheme.colors.ink,
    ) { FaceImage(p.cover, Modifier.matchParentSize()) }
}

/** A face cut from its photo, in a circle. Cached by face id, so each one is cut once per session. */
@Composable
fun FaceImage(crop: FaceCrop, modifier: Modifier) {
    val context = LocalPlatformContext.current
    val request = remember(crop) {
        ImageRequest.Builder(context).data(crop).fetcherFactory(FaceCropFetcher.Factory()).memoryCacheKey("face:${crop.faceId}").build()
    }
    Box(modifier.clip(CircleShape).background(AlbumTheme.colors.surfaceHigh)) {
        AsyncImage(request, contentDescription = null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

/**
 * The top of a person page, styled like LargeTopBar: back, rename and more on a row, then the name in title-xl (or
 * "Add a name"; tap it to name them) and the photo count. More offers merging with another person and hiding.
 * [onOpenPerson] gets the page to show next, as an album id: naming or merging an automatic group makes it a person
 * with a new id.
 */
@Composable
fun PersonHeader(person: PersonAlbum, onBack: () -> Unit, onOpenPerson: (albumId: Long) -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val scope = rememberCoroutineScope()
    val all by People.albums.collectAsState()
    var naming by rememberSaveable { mutableStateOf(false) }
    var merging by rememberSaveable { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(false) }
    // [done] gets the person's id afterwards: by default, its page opens if the id changed
    fun change(note: String, edit: suspend () -> Long, done: (Long) -> Unit = { if (it != person.cluster) onOpenPerson(personAlbumId(it)) }) {
        CrashLog.note(note)
        scope.launch { safely(edit)?.let(done) ?: Toast.makeText(context, COULDNT_SAVE, Toast.LENGTH_SHORT).show() }
    }
    val rename = if (person.name == null) NO_NAME else "Rename"
    Column(Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = Space.s2, bottom = Space.s3)) {
        Row(Modifier.fillMaxWidth().heightIn(min = Space.target), verticalAlignment = Alignment.CenterVertically) {
            AlbumIconButton(Sym.ArrowBack, "Back", onBack, Modifier.offset(x = -Space.s3))
            Spacer(Modifier.weight(1f))
            Row(Modifier.offset(x = Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
                AlbumIconButton(Sym.Edit, rename, { naming = true })
                AlbumIconButton(Sym.MoreVert, "More", { more = true })
            }
        }
        Text(
            personTitle(person),
            Modifier.padding(top = Space.s6).clickable(onClickLabel = rename, role = Role.Button) { naming = true },
            style = AlbumTheme.type.titleXl,
            color = if (person.name == null) c.flashInk else c.ink,
        )
        Text(photoCount(person), Modifier.padding(top = Space.s1), style = AlbumTheme.type.meta, color = c.inkMuted)
    }

    if (more) {
        Sheet({ more = false }, personTitle(person)) {
            MenuRow(Sym.Merge, "Merge with another person", {
                more = false
                merging = true
            }, sub = "When the same person shows up twice", trailing = null)
            MenuRow(
                if (person.hidden) Sym.Visibility else Sym.VisibilityOff,
                if (person.hidden) "Show in People" else "Hide from People",
                {
                    more = false
                    val hide = !person.hidden
                    // Like hiding a folder: they leave the list, so the page closes too
                    change(if (hide) "hide person" else "show person", { People.setHidden(person.cluster, hide) }) { if (hide) onBack() }
                },
                sub = "Their photos stay in the Library".takeIf { !person.hidden },
                trailing = null,
            )
        }
    }
    if (naming) {
        PersonNameSheet(person, all, onDismiss = { naming = false }) { name ->
            naming = false
            change(if (person.name == null) "name person" else "rename person", { People.name(person.cluster, name) })
        }
    }
    if (merging) {
        MergeSheet(person, shownPeople(all).filter { it.cluster != person.cluster }, onDismiss = { merging = false }) { target ->
            merging = false
            change("merge people", { People.merge(person.cluster, target.cluster) })
        }
    }
}

/**
 * Asks for a name, suggesting people already named. Picking or typing one of their names (any case) merges the two,
 * and the button says so.
 */
@Composable
private fun PersonNameSheet(person: PersonAlbum, all: List<PersonAlbum>, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val initial = person.name.orEmpty()
    // Starts with the old name selected, so typing replaces it
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val name = text.text.trim()
    val named = all.filter { it.name != null && it.cluster != person.cluster }
    val same = named.find { it.name.equals(name, ignoreCase = true) }
    val suggestions = if (name.isEmpty() || same != null) emptyList() else named.filter { it.name!!.startsWith(name, ignoreCase = true) }.take(4)
    val enabled = name.isNotEmpty() && name != person.name
    NameSheet(
        if (person.name == null) NO_NAME else "Rename",
        text,
        { text = it },
        confirm = if (same != null) "Merge" else "Save",
        enabled = enabled,
        onDismiss = onDismiss,
        onConfirm = { if (enabled) onDone(name) },
        note = same?.let { "You already have ${it.name}. Saving merges them into one person." },
        capitalization = KeyboardCapitalization.Words,
    ) {
        suggestions.forEach { s ->
            CoverRow(s.name!!, photoCount(s), { text = TextFieldValue(s.name, TextRange(s.name.length)) }, round = true) {
                FaceImage(s.cover, Modifier.matchParentSize())
            }
        }
    }
}

/** Picks who [person] is the same as, then asks before merging (it can't be undone in one tap). */
@Composable
private fun MergeSheet(person: PersonAlbum, others: List<PersonAlbum>, onDismiss: () -> Unit, onMerge: (PersonAlbum) -> Unit) {
    var target by remember { mutableStateOf<PersonAlbum?>(null) }
    target?.let { t ->
        val name = t.name ?: person.name
        ConfirmBar(
            title = if (name != null) "Merge into $name?" else "Merge these people?",
            body = "Their photos will show as one person. Remove any that don't belong with Not ${name ?: "this person"}.",
            confirmLabel = "Merge",
            onConfirm = { onMerge(t) },
            onCancel = { target = null },
            icon = Sym.Merge,
            tone = ConfirmTone.Flash,
        )
        return
    }
    Sheet(onDismiss, "Same person as") {
        if (others.isEmpty()) Text("No one else yet", style = AlbumTheme.type.body, color = AlbumTheme.colors.inkMuted)
        LazyColumn {
            items(others, key = { it.cluster }) { p ->
                CoverRow(p.name ?: NO_NAME, photoCount(p), { target = p }, round = true) { FaceImage(p.cover, Modifier.matchParentSize()) }
            }
        }
    }
}

/**
 * Returns a function that takes photos out of a person ("Not <name>"), then offers Undo in [snackbar]. Doing that to an
 * automatic group makes it a person with a new id, which goes to [onOpen] so its page stays open.
 */
@Composable
fun rememberNotPerson(snackbar: SnackbarHostState, onOpen: (albumId: Long) -> Unit): (PersonAlbum, List<Long>) -> Unit {
    val scope = rememberCoroutineScope()
    return { person, mediaIds ->
        CrashLog.note("not person ${mediaIds.size}")
        scope.launch {
            val change = safely { People.notPerson(person.cluster, mediaIds) } ?: return@launch run { snackbar.showSnackbar(COULDNT_SAVE) }
            if (change.personId != person.cluster) onOpen(personAlbumId(change.personId))
            val undo = snackbar.showSnackbar("Removed ${counted(mediaIds.size, "photo", "photos")} from ${titleOf(person)}", "Undo", duration = SnackbarDuration.Long)
            if (undo == SnackbarResult.ActionPerformed) {
                CrashLog.note("undo not person ${mediaIds.size}")
                safely { People.undo(change) } ?: snackbar.showSnackbar(COULDNT_SAVE)
            }
        }
    }
}

/** The selection bar's "Not <name>" label. */
fun notPersonLabel(person: PersonAlbum) = "Not ${titleOf(person)}"

/** Runs a People change; a database error (full storage, say) is logged and returns null instead of crashing Album. */
private suspend fun <T> safely(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    CrashLog.note("people write failed: $e")
    null
}
