package com.aditya.album

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * [app]'s launcher icon, as the phone shows it, or a stand-in (its initial on its colour) when the app isn't installed.
 * Decorative: a label always sits next to it. Blank for the moment it takes to load, rather than flashing the stand-in.
 */
@Composable
fun AppIcon(app: MediaApp, modifier: Modifier = Modifier, size: Dp = Space.icon) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { (Space.icon * 2).roundToPx() } // one size for every place it shows, drawn down
    val icon by produceState(appIcons[app], app) {
        if (value == null) value = withContext(Dispatchers.IO) { LoadedIcon(launcherIcon(context, app, px)) }.also { appIcons[app] = it }
    }
    when (val bitmap = icon?.bitmap) {
        null -> if (icon == null) Spacer(modifier.size(size)) else StandInIcon(app, modifier.size(size))
        else -> Image(bitmap, null, modifier.size(size))
    }
}

/** The design's stand-in for an app's icon: the app's initial in white on its colour, in a rounded square. */
@Composable
private fun StandInIcon(app: MediaApp, modifier: Modifier) {
    val tint = when (app) {
        MediaApp.WhatsApp, MediaApp.WhatsAppBusiness -> Color(0xFF2E9E5B)
        MediaApp.Telegram -> Color(0xFF2A8BD6)
        else -> AlbumTheme.colors.inkMuted
    }
    Box(modifier.background(tint, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Text(app.name.take(1), style = AlbumTheme.type.label.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = Color.White)
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
    if (app != null) AppIcon(app, modifier, size) else Icon(kind.sym, null, modifier, size = size, tint = tint)
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
private const val LARGEST_FILES = 8 // two rows of four
private val BigBarHeight = 14.dp
private val StorageBarGap = 2.dp
private val DiscSize = 36.dp // as RowIcon's
private val AppIconInDisc = 22.dp

private class StorageModel(
    val parts: List<Pair<StorageKind, Long>>,
    val counts: Map<StorageKind, Int>,
    val free: Long,
    val folders: List<FolderSize>,
    val largest: List<Media>,
)

private fun storageModelOf(media: List<Media>): StorageModel {
    val kindOf = { m: Media -> storageKind(m.isVideo, m.path, m.bucketName, m.name) }
    return StorageModel(
        storageOf(media, kindOf, Media::size),
        media.groupingBy(kindOf).eachCount(),
        StatFs(Environment.getDataDirectory().path).availableBytes, // shared storage lives on the data partition
        topFolders(media.map { it.toShot() }, TOP_FOLDERS),
        media.sortedByDescending { it.size }.take(LARGEST_FILES),
    )
}

/**
 * The storage page, pushed over the tabs: what photos and videos take as one big number over a bar split by kind, a row
 * per kind, the biggest items ([onOpen] opens one) and the biggest folders, then Free up space with what Clean up
 * suggests, which opens it on All ([onOpenCategory] gets null; a [CleanupKind] name would open that category). [report]
 * gets the biggest items, the photos the viewer swipes through when one of them is opened.
 */
@Composable
fun StorageScreen(
    media: List<Media>,
    onBack: () -> Unit,
    onOpenCategory: (String?) -> Unit,
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
    val inGroup = Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s6)

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(c.canvas)) {
        Box(Modifier.statusBarsPadding()) { CollapsedTopBar("Storage", onBack = onBack) }
        val m = model ?: return@Column // a few ms
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + Space.s8)) {
            item(key = "total") {
                HeroNumber(size(m.parts.sumOf { it.second }), "used by photos and videos. ${size(m.free)} is free on this phone.") {
                    // The rows below say the same in words; TalkBack hears the split here in one go
                    val spoken = m.parts.joinToString { (kind, bytes) -> "${kind.label} ${size(bytes)}" }
                    if (m.parts.isNotEmpty()) SplitBar(m.parts, BigBarHeight, Modifier.padding(top = Space.s2).semantics { contentDescription = spoken })
                }
            }
            if (m.parts.isNotEmpty()) item(key = "types") {
                Group(inGroup, title = "By type") {
                    m.parts.forEach { (kind, bytes) ->
                        GroupRow(kind.label, null, sub = itemCount(m.counts[kind] ?: 0), value = size(bytes), leading = { TypeDisc(kind) }, valueColor = c.ink)
                    }
                }
            }
            if (m.largest.isNotEmpty()) {
                item(key = "largest") { SectionHead("Biggest items") }
                items(m.largest.chunked(CLEAN_COLUMNS), key = { "file:${it.first().id}" }) { row ->
                    CleanGridRow(row) { f ->
                        MediaTile(
                            f, selecting = false, selected = false,
                            Modifier.aspectRatio(1f).clickable(role = Role.Button, onClickLabel = "Open") { onOpen(f) },
                            badge = size(f.size),
                        )
                    }
                }
            }
            if (m.folders.isNotEmpty()) item(key = "folders") {
                Group(inGroup, title = "Biggest folders") {
                    m.folders.forEach { f ->
                        GroupRow(f.name, null, sub = itemCount(f.count), value = size(f.bytes), leading = {
                            IconDisc { MediaApp.of(f.name)?.let { AppIcon(it, size = AppIconInDisc) } ?: Icon(Sym.Folder, null, tint = c.ink) }
                        })
                    }
                }
            }
            item(key = "free") {
                Group(inGroup) {
                    GroupRow(
                        "Free up space", { onOpenCategory(null) }, icon = Sym.CleaningServices,
                        sub = summary?.let { if (it.toReview == 0) "Nothing to suggest" else "${size(it.reviewBytes)} suggested in Clean up" }
                            ?: "Looking through your photos",
                    )
                }
            }
        }
    }
}

/**
 * A big figure and one line on what it means (the design's hero number), then [below], such as Storage's bar. Clean up
 * and Storage open on one.
 */
@Composable
fun HeroNumber(figure: String, line: String, modifier: Modifier = Modifier, below: @Composable () -> Unit = {}) {
    val c = AlbumTheme.colors
    Column(
        modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = Space.s4, bottom = Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Text(figure, style = AlbumTheme.type.display, color = c.ink)
        Text(line, style = AlbumTheme.type.body, color = c.inkMuted)
        below()
    }
}

/** A cleanup category's icon on [RowIcon]'s disc: WhatsApp's own launcher icon, else its symbol. */
@Composable
fun KindDisc(kind: CleanupKind) = IconDisc { KindIcon(kind, size = if (kind.app != null) AppIconInDisc else Space.icon, tint = AlbumTheme.colors.ink) }

// A storage kind's icon on the disc
@Composable
private fun TypeDisc(kind: StorageKind) = IconDisc {
    val ink = AlbumTheme.colors.ink
    when (kind) {
        StorageKind.Photos -> Icon(Sym.Image, null, tint = ink)
        StorageKind.Videos -> Icon(Sym.Videocam, null, tint = ink)
        StorageKind.WhatsApp -> AppIcon(MediaApp.WhatsApp, size = AppIconInDisc)
        StorageKind.Screenshots -> Icon(Sym.ScreenshotMonitor, null, tint = ink)
    }
}

// [RowIcon]'s disc around something other than a symbol, such as an app's launcher icon
@Composable
private fun IconDisc(content: @Composable () -> Unit) =
    Box(Modifier.size(DiscSize).background(AlbumTheme.colors.surfaceHigh, CircleShape), contentAlignment = Alignment.Center) { content() }

@Composable
private fun storageColor(kind: StorageKind): Color {
    val c = AlbumTheme.colors
    return when (kind) {
        StorageKind.Photos -> c.ink
        StorageKind.Videos -> c.flashInk
        StorageKind.WhatsApp -> c.lineStrong
        StorageKind.Screenshots -> c.line
    }
}

/** The bar split by kind, [height] tall: Photos ink, Videos flash-ink, WhatsApp line-strong, Screenshots line. */
@Composable
private fun SplitBar(parts: List<Pair<StorageKind, Long>>, height: Dp, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(height).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(StorageBarGap)) {
        parts.forEach { (kind, bytes) -> Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(storageColor(kind))) }
    }
}
