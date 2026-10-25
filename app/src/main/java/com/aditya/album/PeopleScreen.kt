package com.aditya.album

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val SECTION_PEOPLE = 12 // faces in the Explore row; See all shows the rest
private const val NO_NAME = "Add a name"
private const val COULDNT_SAVE = "Couldn't save that change to People"

/** People shown in People, biggest first. With [hidden], the hidden ones instead, however small, so each can be shown again. */
private fun shown(all: List<PersonAlbum>, hidden: Boolean = false) =
    all.filter { it.hidden == hidden && (hidden || isShown(it.name, it.faces)) }.sortedWith(compareByDescending<PersonAlbum> { it.items.size }.thenBy { it.cluster })

private fun titleOf(p: PersonAlbum) = p.name ?: "this person"

private fun scanning(p: IndexProgress) = "Finding people: ${"%,d".format(p.done)} of ${"%,d".format(p.total)} photos checked"

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
 * A row of faces for Explore, biggest first. Shows nothing until the first people are found (or while there are none),
 * but See all stays while only hidden people are left, so hiding can always be undone.
 */
@Composable
fun PeopleSection(onOpenPerson: (albumId: Long) -> Unit, onSeeAll: () -> Unit, modifier: Modifier = Modifier) {
    val all by People.albums.collectAsState()
    val people = remember(all) { shown(all) }
    val anyHidden = remember(all) { all.any { it.hidden } }
    val progress by People.progress.collectAsState()
    if (people.isEmpty() && !anyHidden && progress.total == 0) return
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("People", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (people.isNotEmpty() || anyHidden) TextButton(onClick = onSeeAll) { Text("See all") }
        }
        if (progress.total > 0) {
            Text(scanning(progress), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyRow(contentPadding = PaddingValues(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(people.take(SECTION_PEOPLE), key = { it.cluster }) { p ->
                PersonFaceLabel(p, Modifier.width(72.dp), count = false) { onOpenPerson(p.albumId) }
            }
        }
    }
}

/**
 * Everyone, drawn over the app like Settings: a grid of faces, biggest first, then hidden people at the end.
 * [state] belongs to the caller, so the scroll position survives a visit to a person's page.
 */
@Composable
fun PeopleScreen(state: LazyGridState, onBack: () -> Unit, onOpenPerson: (albumId: Long) -> Unit) {
    val all by People.albums.collectAsState()
    val people = remember(all) { shown(all) }
    val hidden = remember(all) { shown(all, hidden = true) }
    val progress by People.progress.collectAsState()
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {} // catches taps on empty space, which would otherwise reach the screen underneath
            .systemBarsPadding(),
    ) {
        TitleBar("People", onBack = onBack)
        if (progress.total > 0) {
            Text(scanning(progress), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (people.isEmpty() && hidden.isEmpty()) {
            Message("No people yet. Album looks for faces in your photos on this phone, a little at a time in the background.", Modifier)
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Fixed(3),
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            state = state,
            contentPadding = PaddingValues(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(people, key = { it.cluster }) { p -> PersonFaceLabel(p, Modifier, count = true) { onOpenPerson(p.albumId) } }
            if (hidden.isNotEmpty()) {
                item(key = "hidden", span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Hidden people") }
                items(hidden, key = { it.cluster }) { p -> PersonFaceLabel(p, Modifier.alpha(0.6f), count = true) { onOpenPerson(p.albumId) } }
            }
        }
    }
}

/** Round face with the name (or "Add a name") under it, and the photo count when [count]. */
@Composable
private fun PersonFaceLabel(p: PersonAlbum, modifier: Modifier, count: Boolean, onClick: () -> Unit) {
    Column(modifier.clickable(onClickLabel = "Open", onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        FaceImage(p.cover, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(
            p.name ?: NO_NAME,
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.titleSmall,
            color = if (p.name == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (count) Text("${p.items.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A face cut from its photo, in a circle. Cached by face id, so each one is cut once per session. */
@Composable
fun FaceImage(crop: FaceCrop, modifier: Modifier) {
    val context = LocalPlatformContext.current
    val request = remember(crop) {
        ImageRequest.Builder(context).data(crop).fetcherFactory(FaceCropFetcher.Factory()).memoryCacheKey("face:${crop.faceId}").build()
    }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(request, contentDescription = null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

/**
 * The person page's title bar: the name (or "Add a name"), a pencil to name or rename, and a menu to merge with
 * another person or hide. [onOpen] gets the page to show next, as an album id: naming or merging an automatic group
 * makes it a person with a new id.
 */
@Composable
fun PersonTitleBar(person: PersonAlbum, onBack: () -> Unit, onOpen: (albumId: Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by People.albums.collectAsState()
    var naming by rememberSaveable { mutableStateOf(false) }
    var merging by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // [done] gets the person's id afterwards: by default, its page opens if the id changed
    fun change(note: String, edit: suspend () -> Long, done: (Long) -> Unit = { if (it != person.cluster) onOpen(personAlbumId(it)) }) {
        CrashLog.note(note)
        scope.launch { safely(edit)?.let(done) ?: Toast.makeText(context, COULDNT_SAVE, Toast.LENGTH_SHORT).show() }
    }
    TitleBar(person.name ?: NO_NAME, onBack = onBack) {
        IconButton(onClick = { naming = true }) { Icon(Icons.Outlined.Edit, if (person.name == null) NO_NAME else "Rename") }
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Merge with another person") }, onClick = {
                menu = false
                merging = true
            })
            DropdownMenuItem(text = { Text(if (person.hidden) "Show in People" else "Hide from People") }, onClick = {
                menu = false
                val hide = !person.hidden
                // Like Hide on a folder: it leaves the list, so the page closes too
                change(if (hide) "hide person" else "show person", { People.setHidden(person.cluster, hide) }) { if (hide) onBack() }
            })
        }
    }
    if (naming) {
        PersonNameDialog(person, all, onDismiss = { naming = false }) { name ->
            naming = false
            change(if (person.name == null) "name person" else "rename person", { People.name(person.cluster, name) })
        }
    }
    if (merging) {
        MergeSheet(person, shown(all).filter { it.cluster != person.cluster }, onDismiss = { merging = false }) { target ->
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
private fun PersonNameDialog(person: PersonAlbum, all: List<PersonAlbum>, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val initial = person.name.orEmpty()
    // Starts with the old name selected, so typing replaces it
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val name = text.text.trim()
    val named = all.filter { it.name != null && it.cluster != person.cluster }
    val same = named.find { it.name.equals(name, ignoreCase = true) }
    val suggestions = if (name.isEmpty() || same != null) emptyList() else named.filter { it.name!!.startsWith(name, ignoreCase = true) }.take(4)
    val enabled = name.isNotEmpty() && name != person.name
    val done = { if (enabled) onDone(name) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (person.name == null) NO_NAME else "Rename") },
        text = {
            Column {
                OutlinedTextField(
                    text,
                    { text = it },
                    Modifier.focusRequester(focus),
                    label = { Text("Name") },
                    supportingText = { same?.let { Text("You already have ${it.name}. Saving merges them into one person.") } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { done() }),
                    singleLine = true,
                )
                suggestions.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { text = TextFieldValue(s.name!!, TextRange(s.name.length)) }.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FaceImage(s.cover, Modifier.size(36.dp))
                        Text(s.name!!, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            LaunchedEffect(focus) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = done, enabled = enabled) { Text(if (same != null) "Merge" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks who [person] is the same as, then asks before merging (it can't be undone in one tap). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeSheet(person: PersonAlbum, others: List<PersonAlbum>, onDismiss: () -> Unit, onMerge: (PersonAlbum) -> Unit) {
    var target by remember { mutableStateOf<PersonAlbum?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Same person as", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        if (others.isEmpty()) Text("No one else yet", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(others, key = { it.cluster }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { target = p }.padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FaceImage(p.cover, Modifier.size(56.dp))
                    Column {
                        Text(p.name ?: NO_NAME, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.items.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    target?.let { t ->
        val name = t.name ?: person.name
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(if (name != null) "Merge into $name?" else "Merge these people?") },
            text = { Text("Their photos will show as one person. Remove any that don't belong with Not ${name ?: "this person"}.") },
            confirmButton = { TextButton(onClick = { onMerge(t) }) { Text("Merge") } },
            dismissButton = { TextButton(onClick = { target = null }) { Text("Cancel") } },
        )
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
            val undo = snackbar.showSnackbar("Removed ${mediaIds.size} from ${titleOf(person)}", "Undo", duration = SnackbarDuration.Long)
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
