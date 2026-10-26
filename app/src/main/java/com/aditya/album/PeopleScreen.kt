package com.aditya.album

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val NO_NAME = "Add a name"
private const val COULDNT_SAVE = "Couldn't save that change to People"

private val biggestFirst = compareByDescending<PersonAlbum> { it.items.size }.thenBy { it.cluster }

/**
 * People shown in People and Collections, biggest first. With [hidden], the ones hidden from People instead, however small,
 * so each can be shown again. People in Hidden items are in neither.
 */
fun shownPeople(all: List<PersonAlbum>, hidden: Boolean = false) =
    all.filter { !it.locked && it.hidden == hidden && (hidden || isShown(it.name, it.faces)) }.sortedWith(biggestFirst)

/** People in Hidden items, biggest first. */
fun peopleInHiddenItems(all: List<PersonAlbum>) = all.filter { it.locked }.sortedWith(biggestFirst)

/** Everyone who can go to Hidden items: shown in People or hidden from it, biggest first. */
fun hideablePeople(all: List<PersonAlbum>) = all.filter { !it.locked && (it.hidden || isShown(it.name, it.faces)) }.sortedWith(biggestFirst)

private fun titleOf(p: PersonAlbum) = p.name ?: "this person"

/** A person page's title: their name, or the prompt to give one. For the shell's collapsed bar; [PersonHeader] shows the same. */
fun personTitle(person: PersonAlbum) = person.name ?: NO_NAME

private fun photoCount(p: PersonAlbum) = counted(p.items.size, "photo", "photos")

fun scanningText(p: IndexProgress) = "Finding people: ${"%,d".format(p.done)} of ${"%,d".format(p.total)} photos checked"

/** "3 to review": "Same person?" questions waiting. */
fun reviewCount(n: Int) = "${"%,d".format(n)} to review"

/**
 * The person page open at [albumId], or null when it isn't a person page. Watches People only while one is open.
 * A group that just became a person (or merged) is found under its new id, so its page stays open meanwhile. A person
 * in Hidden items is there only while privacy is unlocked, so a page restored later closes instead of showing them.
 */
@Composable
fun rememberPerson(albumId: Long?): PersonAlbum? {
    val cluster = albumId?.let(::clusterOfAlbum) ?: return null
    val people by People.albums.collectAsState()
    val open = Safe.key.collectAsState().value != null
    val person = people.find { it.cluster == cluster } ?: People.movedTo(cluster)?.let { to -> people.find { it.cluster == to } }
    return person?.takeIf { !it.locked || open }
}

/**
 * Everyone, pushed over the tabs: "Same person?" questions when there are any, a grid of faces, biggest first, then
 * hidden people at the end. [state] belongs to the caller, so the scroll position survives a visit to a person's page.
 */
@Composable
fun PeopleScreen(state: LazyGridState, onBack: () -> Unit, onOpenPerson: (albumId: Long) -> Unit, onReviewPeople: () -> Unit) {
    val all by People.albums.collectAsState()
    val people = remember(all) { shownPeople(all) }
    val hidden = remember(all) { shownPeople(all, hidden = true) }
    val progress by People.progress.collectAsState()
    val reviews by remember { People.review }.collectAsState(emptyList())
    val subtitle = counted(people.size, "person", "people") + if (hidden.isEmpty()) "" else " · ${"%,d".format(hidden.size)} hidden"
    PushedGridPage("People", subtitle, state, onBack) {
        if (progress.total > 0) fullRow("scan") { Note(scanningText(progress)) }
        if (reviews.isNotEmpty()) fullRow("review") {
            UtilityTile(
                Sym.Compare, "Same person?", "%,d".format(reviews.size), "Same person?, ${reviewCount(reviews.size)}",
                Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s4),
            ) {
                CrashLog.note("people review")
                onReviewPeople()
            }
        }
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

/** A person as a round AlbumCard: their cover face, name (or "Add a name", muted) and photo count, centred. */
@Composable
fun PersonCard(p: PersonAlbum, modifier: Modifier = Modifier, count: Boolean = true, onClick: () -> Unit) {
    AlbumCard(
        name = p.name ?: NO_NAME,
        meta = if (count) listOf("%,d".format(p.items.size)) else emptyList(),
        description = "${p.name ?: "Unnamed person"}, ${photoCount(p)}",
        onClick = onClick,
        modifier = modifier,
        round = true,
        nameColor = if (p.name == null) AlbumTheme.colors.inkMuted else AlbumTheme.colors.ink,
    ) { FaceImage(p.cover, Modifier.matchParentSize()) }
}

/**
 * Collections' People heading: the title, a quiet "3 to review" while there are "Same person?" questions, and See all
 * when [onSeeAll] is set. Spaced like SectionHead.
 */
@Composable
fun PeopleHead(reviews: Int, onReview: () -> Unit, onSeeAll: (() -> Unit)?) {
    val c = AlbumTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = Space.s6, bottom = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text("People", Modifier.weight(1f), style = AlbumTheme.type.titleMd, color = c.ink)
        if (reviews > 0) HeadLink(reviewCount(reviews), c.inkMuted, onReview)
        onSeeAll?.let { HeadLink("See all", c.flashInk, it) }
    }
}

@Composable
private fun HeadLink(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.s2, vertical = Space.s1),
        style = AlbumTheme.type.label,
        color = color,
        maxLines = 1,
    )
}

/**
 * Collections' People row: the first five of [people] as round faces with their names under them, in one row that
 * never scrolls sideways. Four instead when "Add a name" wouldn't fit under five (a narrow screen, a large font), so
 * the row never shows a clipped prompt. Empty places keep the faces' size.
 */
@Composable
fun PeopleRow(people: List<PersonAlbum>, onOpen: (PersonAlbum) -> Unit) {
    val measurer = rememberTextMeasurer()
    val label = AlbumTheme.type.label
    val prompt = remember(measurer, label) { measurer.measure(NO_NAME, label).size.width }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, bottom = Space.s4)) {
        val five = with(LocalDensity.current) { ((maxWidth - Space.s2 * 4) / 5).toPx() } >= prompt
        val slots = if (five) 5 else 4
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            for (i in 0 until slots) {
                val p = people.getOrNull(i)
                if (p == null) Spacer(Modifier.weight(1f)) else PersonCard(p, Modifier.weight(1f), count = false) { onOpen(p) }
            }
        }
    }
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
 * "Add a name"; tap it to name them) and the photo count. More offers a new cover photo, merging with another person,
 * hiding from People, and Hide person (into Hidden items, after the privacy PIN); for someone in Hidden items, a new
 * cover and Unhide. [onOpenPerson] gets the page to show next, as an album id: naming or merging an automatic group makes
 * it a person with a new id.
 */
@Composable
fun PersonHeader(person: PersonAlbum, onBack: () -> Unit, onOpenPerson: (albumId: Long) -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val scope = rememberCoroutineScope()
    val privacy = rememberPrivacyAsk()
    val all by People.albums.collectAsState()
    var naming by rememberSaveable { mutableStateOf(false) }
    var merging by rememberSaveable { mutableStateOf(false) }
    var covering by rememberSaveable { mutableStateOf(false) }
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
            MenuRow(Sym.Face, "Change cover photo", {
                more = false
                covering = true
            }, sub = "The face shown for them in People", trailing = null)
            if (person.locked) {
                MenuRow(Sym.Visibility, "Unhide", {
                    more = false
                    privacy.run("Unhide ${titleOf(person)}") { Hidden.unhide(context, listOf(person), emptyList()) }
                }, sub = "They and their photos show in the Library again", trailing = null)
            } else {
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
                MenuRow(Sym.Lock, "Hide person", {
                    more = false
                    // They and their photos leave everything but Hidden items, this page too
                    privacy.run("Hide ${titleOf(person)}") {
                        Hidden.hide(context, listOf(person), emptyList())
                        onBack()
                    }
                }, sub = "They and their photos show only in Hidden items", trailing = null)
            }
        }
    }
    if (covering) {
        CoverSheet(person, onDismiss = { covering = false }) { face ->
            covering = false
            change(if (face == null) "cover automatic" else "cover pinned", { People.setCover(person.cluster, face) })
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
 * Change cover photo: the person's faces, best covers first; tapping one pins it, marked with a check. While one is
 * pinned, Choose automatically hands the choice back to Album.
 */
@Composable
private fun CoverSheet(person: PersonAlbum, onDismiss: () -> Unit, onPick: (FaceTile?) -> Unit) {
    val faces by remember(person.cluster) { People.facesOf(person.cluster) }.collectAsState(emptyList())
    Sheet(onDismiss, "Change cover photo") {
        if (person.coverPinned) {
            MenuRow(Sym.Refresh, "Choose automatically", { onPick(null) }, sub = "Album shows their clearest photo", trailing = null)
        }
        LazyVerticalGrid(
            GridCells.Fixed(4),
            Modifier.padding(top = Space.s2),
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            items(faces, key = { it.id }) { f ->
                val current = f.id == person.cover.faceId
                Box(
                    Modifier.aspectRatio(1f)
                        .clickable(onClickLabel = "Use as cover photo", role = Role.Button) { onPick(f) }
                        .semantics { contentDescription = if (current) "Cover photo now" else "Photo of ${titleOf(person)}" },
                ) {
                    FaceImage(FaceCrop(f.id, f.mediaId, f.box), Modifier.matchParentSize())
                    if (current) SelectCheck(true, Modifier.align(Alignment.TopEnd))
                }
            }
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
    // Names merge only on the same side of Hidden items (see People.name), and hidden people are never suggested outside it
    val named = all.filter { it.name != null && it.cluster != person.cluster && it.locked == person.locked }
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
 * "Same person?": two groups of faces Album thinks may be one person, side by side, with Different (never asked
 * again), Not sure (skipped) and Same (merged into the bigger one) where the thumb is. Pushed over the tabs; draws its
 * own background, insets and Back.
 */
@Composable
fun PeopleReviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val scope = rememberCoroutineScope()
    val pairs by remember { People.review }.collectAsState(null)
    // Answered pairs leave at once, before the clustering run that follows the answer catches up
    var answered by remember { mutableStateOf(emptySet<Set<Long>>()) }
    val open = pairs.orEmpty().filter { setOf(it.a.cluster, it.b.cluster) !in answered }
    val pair = open.firstOrNull()
    fun answer(note: String, p: PeoplePair, edit: suspend () -> Unit) {
        CrashLog.note(note)
        val key = setOf(p.a.cluster, p.b.cluster)
        answered = answered.plusElement(key)
        scope.launch {
            if (safely(edit) == null) {
                answered = answered.minusElement(key)
                Toast.makeText(context, COULDNT_SAVE, Toast.LENGTH_SHORT).show()
            }
        }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(c.canvas).pointerInput(Unit) {}.systemBarsPadding()) {
        LargeTopBar("Same person?", subtitle = pairs?.let { reviewCount(open.size) }, onBack = onBack)
        // Keyed by the two groups, so a pair whose photos change doesn't fade in again
        AnimatedContent(
            pair,
            Modifier.weight(1f),
            transitionSpec = { if (still) EnterTransition.None togetherWith ExitTransition.None else fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
            contentKey = { it?.let { p -> p.a.cluster to p.b.cluster } },
            label = "pair",
        ) { p ->
            when {
                p != null -> Row(
                    Modifier.fillMaxSize().padding(horizontal = Space.s4),
                    horizontalArrangement = Arrangement.spacedBy(Space.s4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReviewSide(p.a, Modifier.weight(1f))
                    ReviewSide(p.b, Modifier.weight(1f))
                }
                pairs != null -> EmptyState(Sym.Groups, "Nothing to review", "When two groups of faces may be one person, Album asks you here.")
                else -> Box(Modifier.fillMaxSize()) // loading: a moment on first open only
            }
        }
        if (pair != null) {
            Row(Modifier.fillMaxWidth().padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                val tall = Modifier.heightIn(min = Space.target + Space.s2)
                AlbumButton("Different", { answer("review different", pair) { People.different(pair.a.cluster, pair.b.cluster) } }, Modifier.weight(1f).then(tall))
                AlbumButton("Not sure", { answer("review not sure", pair) { People.notSure(pair) } }, tall, ButtonVariant.Quiet)
                AlbumButton("Same", { answer("review same", pair) { People.merge(pair.b.cluster, pair.a.cluster) } }, Modifier.weight(1.5f).then(tall), ButtonVariant.Primary)
            }
        }
    }
}

/** One side of a "Same person?" pair: the group's cover face, its name if it has one, and how many photos. */
@Composable
private fun ReviewSide(p: PersonAlbum, modifier: Modifier) {
    val c = AlbumTheme.colors
    Column(
        modifier.semantics(mergeDescendants = true) { contentDescription = "${p.name ?: "Unnamed person"}, ${photoCount(p)}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        FaceImage(p.cover, Modifier.fillMaxWidth().aspectRatio(1f))
        Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
            p.name?.let { Text(it, style = AlbumTheme.type.titleMd, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
            Text(photoCount(p), style = AlbumTheme.type.meta, color = c.inkMuted)
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
