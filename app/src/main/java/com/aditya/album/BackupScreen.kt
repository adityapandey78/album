package com.aditya.album

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Date

// Settings > Backup: where the photos go, how far the backup is, and the few choices. Which albums, and the photos not
// backed up yet, open pages of their own, so this one stays short.

private enum class BackupPage { Main, Albums, Pending }

/** The row in Settings > Photos and videos that opens Backup, saying where the backup stands. */
@Composable
fun BackupRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s = Backup.settings.collectAsState().value
    val running = Backup.progress.collectAsState().value
    val sub = when {
        s == null -> null // a moment, at launch
        s.tree == null -> "Copy photos and videos to an SD card or a USB drive"
        running != null -> backingUpLine(running)
        s.last > 0 -> "Last backed up ${lastBackup(context, s.last)}"
        else -> "Not backed up yet"
    }
    GroupRow("Backup", onOpen, modifier, icon = Sym.SdCard, sub = sub)
}

private fun backingUpLine(p: IndexProgress) = "Backing up · ${countLine(p)}"

private fun countLine(p: IndexProgress) = "${"%,d".format(p.done)} of ${"%,d".format(p.total)}"

private fun lastBackup(context: android.content.Context, at: Long): String {
    val clock = android.text.format.DateFormat.getTimeFormat(context) // the phone's 12- or 24-hour clock
    return backedUpWhen(at, System.currentTimeMillis(), ZoneId.systemDefault()) { clock.format(Date(it)) }
}

/**
 * Backup, pushed from Settings: the drive and how far the backup has got, Back up now, the automatic switch, which
 * albums, and what isn't backed up yet. [onOpen] opens a photo from that grid in the viewer.
 */
@Composable
fun BackupScreen(onBack: () -> Unit, onOpen: (Media) -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    var page by rememberSaveable { mutableStateOf(BackupPage.Main) }
    val s = Backup.settings.collectAsState().value
    val running = Backup.progress.collectAsState().value
    val volumes by Backup.volumes.collectAsState()
    val media = Gallery.media.collectAsState().value
    val hidden = Hidden.state.collectAsState().value
    // Looked at again when a drive goes in or out, a backup starts or ends, and when the page is back on screen
    var looks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        looks++
        onPauseOrDispose {}
    }
    val drive by produceState<Drive?>(null, s?.tree, s?.folder, volumes, running == null, looks) { value = withContext(Dispatchers.IO) { Backup.drive() } }
    val pending by produceState<Pending?>(null, s?.tree, s?.choice, media, hidden, running == null) {
        value = if (media != null && hidden != null) Backup.pending(media, hidden) else null
    }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            scope.launch {
                withContext(Dispatchers.IO) { Backup.choose(tree) }?.let { Messages.post(context, it) }
            }
        }
    }
    val choose = {
        CrashLog.note("backup: open folder picker")
        runCatching { pick.launch(null) }.onFailure { Messages.post(context, "No app on this phone can choose a folder") }
        Unit
    }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes) // KB, MB or GB as fits, in the phone's locale

    if (s != null && media != null && hidden != null) {
        when (page) {
            BackupPage.Albums -> return AlbumsPage(s.choice.albums, media, hidden) { page = BackupPage.Main }
            BackupPage.Pending -> {
                val items = pending?.items.orEmpty()
                LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) page = BackupPage.Main } // all backed up meanwhile
                return PendingPage(items, hidden, onOpen) { page = BackupPage.Main }
            }
            BackupPage.Main -> {}
        }
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(c.canvas).systemBarsPadding()) {
        CollapsedTopBar("Backup", subtitle = s?.last?.takeIf { it > 0 }?.let { "Last backed up ${lastBackup(context, it)}" }, onBack = onBack)
        if (s == null) return@Column // a moment, at launch
        if (s.tree == null) {
            EmptyState(
                Sym.SdCard,
                "Back up to a drive",
                "Copy your photos and videos to an SD card, a USB drive or a folder you choose, with their folders and names. Nothing is uploaded.",
            ) { AlbumButton("Choose a folder", choose, variant = ButtonVariant.Primary) }
            return@Column
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(top = Space.s4, bottom = Space.s8),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            StatusCard(s, running, drive, pending, ::size, choose, Modifier.padding(horizontal = Space.s4))
            if (running == null && s.failed > 0) {
                if (s.tooBig > 0) {
                    Note("${counted(s.tooBig, "video is", "videos are")} over 4 GB, too big for this drive's format. A drive formatted as exFAT can hold them.")
                }
                if (s.failed > s.tooBig) Note("${counted(s.failed - s.tooBig, "item", "items")} couldn't be copied last time. Album tries again next time.")
            }

            val d = drive
            val where = s.folder?.let(::ownPath)?.trimEnd('/').orEmpty() // "Backups/Album backup", or "" for a whole drive
            MenuRow(
                if (s.onPhone) Sym.PhoneAndroid else Sym.SdCard,
                s.drive,
                choose,
                Modifier.padding(horizontal = Space.s3),
                sub = listOfNotNull(
                    where.ifEmpty { null },
                    if (d?.reach == Reach.Missing) "Not connected" else d?.free?.let { "${size(it)} free" },
                ).joinToString(" · ").ifEmpty { null },
            )
            val p = pending
            if (running == null && p != null && p.items.isNotEmpty() && d?.reach != Reach.Lost) {
                MenuRow(
                    Sym.HourglassEmpty, "Not backed up yet", { page = BackupPage.Pending }, Modifier.padding(horizontal = Space.s3),
                    sub = "${itemCount(p.items.size)} · ${size(p.bytes)}",
                )
            }
            val albums = s.choice.albums
            // Once per Library change, not on every step of a running backup
            val albumsLine = remember(media, albums) {
                if (albums == null) "All albums" else media?.let(::albumsOf)?.let { all -> "${all.count { it.key in albums }} of ${counted(all.size, "album", "albums")}" }
            }
            MenuRow(Sym.PhotoAlbum, "Albums", { page = BackupPage.Albums }, Modifier.padding(horizontal = Space.s3), sub = albumsLine)
            SwitchRow(
                "Back up automatically",
                if (s.onPhone) "Once a day while your phone is charging." else "When you connect this drive, and once a day while charging if it stays connected.",
                s.auto,
                Backup::setAuto,
                Modifier.padding(horizontal = Space.s4),
                icon = Sym.Schedule,
            )
            if (hidden != null && (!hidden.isEmpty || s.choice.hidden)) {
                val ask = rememberPrivacyAsk()
                SwitchRow(
                    "Include hidden items",
                    "Also copies everything in Hidden items. Changing this asks for your PIN or fingerprint.",
                    s.choice.hidden,
                    { on -> ask.run(if (on) "Back up hidden items" else "Stop backing up hidden items") { Backup.setHidden(on) } },
                    Modifier.padding(horizontal = Space.s4),
                    icon = Sym.VisibilityOff,
                )
            }
            if (s.onPhone) Note("This folder is on this phone, so it won't help if the phone is lost or broken.")
            Note(
                "Album only adds to the backup and never deletes from it, so photos you later delete, hide or move to the Private " +
                    "safe stay on the drive. Private safe photos are never copied.",
            )
        }
    }
}

/** Where the backup stands, in a card at the top: one line of what's happening, one of detail, and the one thing to do. */
@Composable
private fun StatusCard(
    s: BackupSettings,
    running: IndexProgress?,
    drive: Drive?,
    pending: Pending?,
    size: (Long) -> String,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val left = pending?.items?.size ?: 0
    // Title, detail, and the button: label to what it does
    val (title, detail, button) = when {
        running != null -> Triple("Backing up", "${countLine(running)} · ${s.drive}", "Stop" to Backup::stop)
        drive == null || pending == null -> return // a moment
        drive.reach == Reach.Lost -> Triple(
            "Album can't open the backup folder",
            "It may have been deleted, the drive formatted, or Album's access taken back. Choose the folder again, or another one.",
            "Choose a folder" to onChoose,
        )
        drive.reach == Reach.Missing -> Triple(
            "${s.drive} isn't connected",
            if (s.stop == BackupStop.Unplugged) "The backup carries on when you connect it." else "Connect it to back up.",
            null,
        )
        s.stop == BackupStop.Full && left > 0 -> Triple(
            "${s.drive} is full",
            "${itemCount(left)} (${size(pending.bytes)}) still to back up. Free up space on it, or choose another drive.",
            "Try again" to Backup::now,
        )
        pending.chosen == 0 -> Triple("Nothing to back up", "No photos or videos in the albums you chose.", null)
        left == 0 -> Triple("Everything is backed up", "${itemCount(pending.chosen)} on ${s.drive}", null)
        else -> Triple(
            "${itemCount(left)} not backed up yet",
            listOfNotNull(size(pending.bytes), drive.free?.let { "${size(it)} free on ${s.drive}" }).joinToString(" · "),
            "Back up ${itemCount(left)}" to Backup::now,
        )
    }
    Column(
        modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(Space.radiusMd)).padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Text(title, style = type.titleMd, color = c.ink)
        Text(detail, style = type.body, color = c.inkMuted)
        running?.let { ProgressLine(it.done.toFloat() / it.total.coerceAtLeast(1), Modifier.padding(top = Space.s1)) }
        button?.let { (label, onClick) ->
            AlbumButton(
                label, onClick, Modifier.padding(top = Space.s2),
                variant = if (running != null) ButtonVariant.Tonal else ButtonVariant.Primary,
            )
        }
    }
}

/**
 * Which albums to back up: all of them (new ones too), or the ones ticked. Turning All albums off ticks every album, so
 * nothing drops out until it's unticked. Hidden items aren't listed: their switch is on the Backup page.
 */
@Composable
private fun AlbumsPage(chosen: Set<String>?, media: List<Media>, hidden: HiddenState, onBack: () -> Unit) {
    val c = AlbumTheme.colors
    val albums = remember(media, hidden) { albumsOf(media).filterNot { it.key in hidden.folders } }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(c.canvas).systemBarsPadding()) {
        CollapsedTopBar("Albums to back up", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = Space.s2, bottom = Space.s8)) {
            item(key = "all") {
                SwitchRow(
                    "All albums", "New albums are backed up too.", chosen == null,
                    { all -> Backup.setAlbums(if (all) null else albums.mapTo(HashSet()) { it.key }) },
                    Modifier.padding(horizontal = Space.s4), icon = Sym.PhotoLibrary,
                )
            }
            if (chosen != null) {
                items(albums, key = { it.id }) { a ->
                    val on = a.key in chosen
                    MenuRow(
                        Sym.Folder, a.name, { Backup.setAlbums(if (on) chosen - a.key else chosen + a.key) },
                        // The check carries the choice; the tint only backs it up
                        Modifier.padding(horizontal = Space.s3)
                            .then(if (on) Modifier.background(c.flashSoft, RoundedCornerShape(Space.radiusMd)) else Modifier)
                            .semantics { stateDescription = if (on) "Backed up" else "Not backed up" },
                        sub = countOf(a.count, "item"),
                        trailing = if (on) Sym.Check else null,
                    )
                }
            }
        }
    }
}

/** The photos and videos not backed up yet, as a grid. Hidden items are counted but never shown here. */
@Composable
private fun PendingPage(items: List<Media>, hidden: HiddenState, onOpen: (Media) -> Unit, onBack: () -> Unit) {
    val shown = remember(items, hidden) { items.withoutHidden(hidden) }
    val columns = rememberColumns("columns_backup")
    val grid = rememberLazyGridState()
    val title = "Not backed up yet"
    val left = items.size - shown.size
    val subtitle = countOf(shown.size, "item") + if (left > 0) ", and $left in Hidden items" else ""
    val bars = WindowInsets.systemBars.asPaddingValues()
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(AlbumTheme.colors.canvas)) {
        MediaGrid(
            shown, columns, grid, emptySet(), onOpen, {}, // nothing to do with a selection here
            PaddingValues(top = bars.calculateTopPadding(), bottom = bars.calculateBottomPadding() + Space.s8),
            header = { LargeTopBar(title, subtitle = subtitle, onBack = onBack) },
        )
        val collapsed by remember(grid) { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
        CollapsingBar(collapsed, Modifier.statusBarsPadding()) { CollapsedTopBar(title, subtitle = subtitle, onBack = onBack) }
    }
}
