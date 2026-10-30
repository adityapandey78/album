package com.aditya.album

import android.content.Context
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Apps that keep the photos and videos they save in folders of their own, by a word in those folders' names. [packages]
 * in order of preference: the first one installed lends its launcher icon. Each needs a <queries> entry in the manifest,
 * or Android hides it from Album. Business first, so its own folders get its own icon.
 */
enum class MediaApp(private val folder: String, val packages: List<String>) {
    WhatsAppBusiness("whatsapp business", listOf("com.whatsapp.w4b")),
    WhatsApp("whatsapp", listOf("com.whatsapp", "com.whatsapp.w4b")),
    Telegram("telegram", listOf("org.telegram.messenger")),
    Instagram("instagram", listOf("com.instagram.android")),
    Snapchat("snapchat", listOf("com.snapchat.android")),
    Messenger("messenger", listOf("com.facebook.orca")),
    Facebook("facebook", listOf("com.facebook.katana")),
    ;

    companion object {
        /** The app a folder (its name, as albums show it) belongs to, if any. */
        fun of(folder: String) = entries.find { folder.contains(it.folder, ignoreCase = true) }
    }
}

// An app's launcher icon, or none of its packages installed. One per app for the life of the process; main thread only.
private class LoadedIcon(val bitmap: ImageBitmap?)

private val appIcons = HashMap<MediaApp, LoadedIcon>()

/**
 * [app]'s launcher icon, as the phone shows it, or [fallback] when the app isn't installed. Decorative: a label always sits
 * next to it. Blank for the moment it takes to load, rather than flashing the fallback first.
 */
@Composable
fun AppIcon(app: MediaApp, fallback: Sym, modifier: Modifier = Modifier, size: Dp = Space.icon, tint: Color = AlbumTheme.colors.inkMuted) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { (Space.icon * 2).roundToPx() } // one size for every place it shows, drawn down
    val icon by produceState(appIcons[app], app) {
        if (value == null) value = withContext(Dispatchers.IO) { LoadedIcon(launcherIcon(context, app, px)) }.also { appIcons[app] = it }
    }
    when (val bitmap = icon?.bitmap) {
        null -> if (icon == null) Spacer(modifier.size(size)) else Icon(fallback, null, modifier, size = size, tint = tint)
        else -> Image(bitmap, null, modifier.size(size))
    }
}

// The first installed package's icon at [px] square. Uninstalling or installing the app shows on the next launch.
private fun launcherIcon(context: Context, app: MediaApp, px: Int): ImageBitmap? = app.packages.firstNotNullOfOrNull { name ->
    try {
        context.packageManager.getApplicationIcon(name).toBitmap(px, px).asImageBitmap()
    } catch (e: PackageManager.NameNotFoundException) {
        null // not installed (or not in the manifest's <queries>, so not visible)
    }
}

/** A cleanup category's icon: its app's launcher icon (WhatsApp), else its symbol. */
@Composable
fun KindIcon(kind: CleanupKind, modifier: Modifier = Modifier, size: Dp = Space.icon, tint: Color = AlbumTheme.colors.inkMuted) {
    val app = kind.app
    if (app != null) AppIcon(app, kind.sym, modifier, size, tint) else Icon(kind.sym, null, modifier, size = size, tint = tint)
}

/** A folder's share of the space: its name as albums show it, how many items, how many bytes. */
data class FolderSize(val name: String, val count: Int, val bytes: Long)

/** The [n] folders taking the most space, biggest first. Folders are told apart by path, since two can share a name. */
fun topFolders(shots: List<Shot>, n: Int): List<FolderSize> =
    shots.groupBy { it.path }.values
        .map { items -> FolderSize(items.first().bucket, items.size, items.sumOf { it.size }) }
        .sortedByDescending { it.bytes }
        .take(n)

private const val TOP_FOLDERS = 5
private const val LARGEST_FILES = 10
private val StorageBarHeight = 10.dp
private val StorageBarGap = 2.dp
private val LegendDot = 8.dp
private val LegendIcon = 16.dp

private class StorageModel(
    val parts: List<Pair<StorageKind, Long>>,
    val photos: Int,
    val videos: Int,
    val folders: List<FolderSize>,
    val largest: List<Media>,
)

private fun storageModelOf(media: List<Media>) = StorageModel(
    storageOf(media, { storageKind(it.isVideo, it.path, it.bucketName, it.name) }, Media::size),
    media.count { !it.isVideo },
    media.count { it.isVideo },
    topFolders(media.map { it.toShot() }, TOP_FOLDERS),
    media.sortedByDescending { it.size }.take(LARGEST_FILES),
)

/**
 * The storage dashboard, pushed over the tabs: what photos and videos take (a segmented bar and legend), how much each
 * cleanup category would free (each row opens Clean up on that category: [onOpenCategory] gets a [CleanupKind] name),
 * the biggest folders and the largest files ([onOpen] opens one). [report] gets the largest files, the photos the viewer
 * swipes through when one of them is opened.
 */
@Composable
fun StorageScreen(
    media: List<Media>,
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpen: (Media) -> Unit = {},
    report: (List<Media>) -> Unit = {},
) {
    val c = AlbumTheme.colors
    val context = LocalContext.current
    val summary by Cleanup.summary.collectAsState()
    // A pass over the whole Library stays off the main thread, like For you's
    val model by produceState<StorageModel?>(null, media) { value = withContext(Dispatchers.Default) { storageModelOf(media) } }
    LaunchedEffect(model) { model?.let { report(it.largest) } }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes) // KB, MB or GB as fits, in the phone's locale
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(c.canvas)) {
        val m = model
        Box(Modifier.statusBarsPadding()) {
            CollapsedTopBar("Storage", subtitle = m?.let { "${size(it.parts.sumOf { p -> p.second })} used by photos and videos" }, onBack = onBack)
        }
        if (m == null) return@Column // a few ms
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = Space.s3, bottom = bottom + Space.s8)) {
            item(key = "bar") { StorageBar(m, ::size, Modifier.padding(horizontal = Space.s4)) }

            item(key = "free") { SectionHead("Free up space") }
            val kinds = CleanupKind.entries.mapNotNull { kind -> summary?.kinds?.get(kind)?.takeIf { it.count > 0 }?.let { kind to it } }
            when {
                summary == null -> item(key = "looking") { Note("Looking for copies, similar shots and blurry photos. This stays on this phone.") }
                kinds.isEmpty() -> item(key = "clean") { Note("Nothing to clean up right now.") }
                else -> items(kinds, key = { "kind:${it.first}" }) { (kind, n) ->
                    // The same rows as For you's Clean up, so a category looks the same wherever it shows
                    LinkRow(
                        { KindIcon(kind) }, kind.label,
                        caption = if (kind.suggested) "Frees ${size(n.bytes)}" else "Uses ${size(n.bytes)}",
                        count = n.count,
                        onClickLabel = "Review",
                        onClick = { onOpenCategory(kind.name) },
                        modifier = Modifier.padding(horizontal = Space.s1),
                    )
                }
            }

            item(key = "folders") { SectionHead("Biggest folders") }
            items(m.folders) { f ->
                SizeRow(f.name, itemCount(f.count), size(f.bytes)) {
                    MediaApp.of(f.name)?.let { AppIcon(it, Sym.Folder) } ?: Icon(Sym.Folder, null, tint = c.inkMuted)
                }
            }

            item(key = "largest") { SectionHead("Largest files") }
            items(m.largest, key = { "file:${it.id}" }) { f ->
                SizeRow(f.name, f.bucketName, size(f.size), onClick = { onOpen(f) }) {
                    Thumbnail(f, Modifier.size(Space.target).clip(RoundedCornerShape(Space.s2)))
                }
            }
        }
    }
}

/** The total, then the bar split by kind and its legend. */
@Composable
private fun StorageBar(m: StorageModel, size: (Long) -> String, modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val shape = RoundedCornerShape(Space.radiusLg)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Text(size(m.parts.sumOf { it.second }), style = type.titleXl, color = c.ink)
            Text("${counted(m.photos, "photo", "photos")} and ${counted(m.videos, "video", "videos")}", style = type.caption, color = c.inkMuted)
        }
        if (m.parts.isNotEmpty()) StorageSplit(m.parts, size)
    }
}

/**
 * A bar split by kind (Photos ink, Videos flash-ink, WhatsApp line-strong, Screenshots line) and its legend, a dot per kind
 * and, for an app's kind, the app's icon. Storage and For you's storage card both show it.
 */
@Composable
fun StorageSplit(parts: List<Pair<StorageKind, Long>>, size: (Long) -> String) {
    val c = AlbumTheme.colors
    fun colorOf(kind: StorageKind) = when (kind) {
        StorageKind.Photos -> c.ink
        StorageKind.Videos -> c.flashInk
        StorageKind.WhatsApp -> c.lineStrong
        StorageKind.Screenshots -> c.line
    }
    // The legend below says the same in words, so the bar itself needs nothing for TalkBack
    Row(Modifier.fillMaxWidth().height(StorageBarHeight).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(StorageBarGap)) {
        parts.forEach { (kind, bytes) -> Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(colorOf(kind))) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        parts.forEach { (kind, bytes) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Box(Modifier.size(LegendDot).background(colorOf(kind), CircleShape))
                kind.app?.let { AppIcon(it, Sym.Folder, size = LegendIcon) }
                Text("${kind.label} ${size(bytes)}", style = AlbumTheme.type.caption, color = c.inkMuted)
            }
        }
    }
}

/** A folder or a file: [leading] (icon or thumbnail), its name and a second line, its size on the right. */
@Composable
private fun SizeRow(label: String, sub: String, size: String, onClick: (() -> Unit)? = null, leading: @Composable () -> Unit) {
    val c = AlbumTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open", onClick = onClick) else Modifier)
            .padding(horizontal = Space.s4, vertical = Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(label, style = AlbumTheme.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = AlbumTheme.type.caption, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(size, style = AlbumTheme.type.label, color = c.ink)
    }
}
