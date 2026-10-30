package com.aditya.album

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import androidx.glance.layout.Alignment as GlanceAlignment
import androidx.glance.layout.Box as GlanceBox
import androidx.glance.layout.Column as GlanceColumn
import androidx.glance.layout.Row as GlanceRow
import androidx.glance.layout.fillMaxSize as glanceFillMaxSize
import androidx.glance.layout.fillMaxWidth as glanceFillMaxWidth
import androidx.glance.layout.padding as glancePadding
import androidx.glance.text.Text as GlanceText

// Photo widgets: a person, an album or collection, or Favourites, taking turns through their photos a few times a day.
// A widget keeps only an album id (see widgetAlbum), never a name or a photo: a renamed album keeps its widget, and one
// moved into Hidden items just asks to be chosen again, without naming it. Photos come from visibleMedia, so Hidden
// items and the Private safe never reach the home screen.

private const val ALBUM_KEY = "widget_album_" // + the widget id, in the "settings" prefs
private const val TURN_KEY = "widget_turn" // how many times the photos have moved on, shared by every widget
private const val ROTATE_WORK = "photo-widgets"
private const val ROTATE_HOURS = 6L // new photos four times a day at most; a change in the Library redraws without moving on
private const val RECENT = 40 // a person widget's photos: their newest
private const val CELL_PX = 384 // a quarter of a large widget; the first photo, alone on a small one, gets WIDGET_THUMB_PX
private const val PEOPLE_MS = 5_000L // People is empty until the face index is read; someone not there by then has gone

// The layouts, every cell about square: one photo, two side by side or stacked, or four in a grid
private val Small = DpSize(110.dp, 110.dp)
private val Wide = DpSize(250.dp, 110.dp)
private val Tall = DpSize(110.dp, 250.dp)
private val Large = DpSize(250.dp, 250.dp)
private val Gutter = 2.dp // the grid's

/** What a photo widget can show. Each kind has its own receiver, so its own entry in the launcher's widget list. */
enum class WidgetKind(val label: String) { Person("Person"), Album("Album"), Favourites("Favourites") }

/** An album as a widget shows it: [title] over [items], newest first. */
class WidgetAlbum(val title: String, val items: List<Media>)

/**
 * The album behind widget album id [albumId], or null when it's gone or in Hidden items: a person who was merged away,
 * locked or never named, a deleted collection, a folder with nothing left. [media] is already without Hidden items;
 * [people] and [collections] are People's and Collections' lists, needed only for those ids.
 */
fun widgetAlbumOf(albumId: Long, media: List<Media>, people: List<PersonAlbum>, collections: List<CollectionAlbum>): WidgetAlbum? {
    clusterOfAlbum(albumId)?.let { cluster ->
        val person = people.find { it.cluster == cluster }?.takeIf { !it.locked } ?: return null
        return person.name?.let { WidgetAlbum(it, person.items) }
    }
    collectionIdOf(albumId)?.let { id -> return collections.find { it.collection.id == id }?.let { WidgetAlbum(it.name, it.items) } }
    if (albumId == Special.Favourites.id) return WidgetAlbum(Special.Favourites.title, media.filter { it.isFavorite })
    val items = media.filter { it.bucketId == albumId }
    return items.firstOrNull()?.let { WidgetAlbum(it.bucketName, items) }
}

/**
 * [count] photos of [pool] for turn [turn], or all of them when there are fewer. They're spread across the pool, so a burst
 * of near-identical shots never fills a widget, and each turn starts somewhere new (golden-ratio steps), so two turns in a
 * row look different and every photo comes round.
 */
fun <T> widgetPhotos(pool: List<T>, turn: Long, count: Int): List<T> {
    if (pool.isEmpty() || count <= 0) return emptyList()
    val n = minOf(count, pool.size)
    val start = ((turn * GOLDEN).mod(1.0) * pool.size).toInt()
    return List(n) { pool[(start + it * pool.size / n) % pool.size] }
}

private const val GOLDEN = 0.6180339887498949

/** The photos a widget of [album] takes turns through: a person's newest [RECENT], anything else's all. Never videos. */
fun widgetPool(album: WidgetAlbum, person: Boolean): List<Media> = album.items.filter { !it.isVideo }.let { if (person) it.take(RECENT) else it }

/** The album widget [widgetId] was set to, or null before one is chosen (always, for Favourites). */
fun widgetAlbum(context: Context, widgetId: Int): Long? {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    return if (prefs.contains(ALBUM_KEY + widgetId)) prefs.getLong(ALBUM_KEY + widgetId, 0) else null
}

private val receivers = mapOf(
    WidgetKind.Person to PersonWidgetReceiver::class.java,
    WidgetKind.Album to AlbumWidgetReceiver::class.java,
    WidgetKind.Favourites to FavouritesWidgetReceiver::class.java,
)

/** Every home-screen widget's receiver, the Memories one too: for whatever finds or switches off all of them. */
val widgetReceivers: List<Class<out GlanceAppWidgetReceiver>> = listOf(MemoryWidgetReceiver::class.java) + receivers.values

/** The kind of Album's widget [widgetId], or null when it isn't one of Album's photo widgets. */
private fun widgetKindOf(context: Context, widgetId: Int): WidgetKind? {
    val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)?.provider ?: return null
    return receivers.entries.find { ComponentName(context, it.value) == provider }?.key
}

/** What a photo widget shows: its title over up to four photos, or over [line] when there are none to show. */
private class PhotoShown(val title: String, val line: String?, val photos: List<Bitmap>, val open: Intent)

/** A home-screen widget of one album's photos, taking turns a few times a day. [kind] says what it can be set to. */
abstract class PhotoWidget(private val kind: WidgetKind) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(Small, Wide, Tall, Large))

    companion object {
        /** Redraws every photo widget with what it should show now; part of [MemoryWidget.refresh]. */
        suspend fun updateAll(context: Context) = listOf(PersonWidget(), AlbumWidget(), FavouritesWidget()).forEach { widget ->
            runCatching { widget.updateAll(context) }.onFailure { CrashLog.note("photo widget refresh: $it") }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val (shown, colors) = withContext(Dispatchers.IO) {
            val shown = runCatching { load(context, widgetId) }.onFailure { CrashLog.note("photo widget: $it") }.getOrNull()
            (shown ?: noAccess(context)) to widgetColors(context)
        }
        provideContent { Content(shown, colors) }
    }

    private fun noAccess(context: Context) =
        PhotoShown(kind.label, "Open Album to see your photos here.", emptyList(), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private suspend fun load(context: Context, widgetId: Int): PhotoShown {
        val media = visibleMedia(context) ?: return noAccess(context)
        val albumId = widgetAlbum(context, widgetId) ?: Special.Favourites.id.takeIf { kind == WidgetKind.Favourites }
        val person = albumId?.let(::clusterOfAlbum) != null
        val album = albumId?.let { widgetAlbumOf(it, media, if (person) peopleNow() else emptyList(), collectionsNow(it, media)) }
        if (albumId == null || album == null) {
            // Tapping chooses again, so a widget whose album went (or went into Hidden items) is one tap from working
            val choose = Intent(context, WidgetConfigureActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PhotoShown(kind.label, if (kind == WidgetKind.Person) "Tap to choose who it shows." else "Tap to choose an album.", emptyList(), choose)
        }
        val open = openAlbumIntent(context, albumId)
        val pool = widgetPool(album, person)
        if (pool.isEmpty()) return PhotoShown(album.title, if (albumId == Special.Favourites.id) Special.Favourites.emptyText else "No photos here yet.", emptyList(), open)
        val turn = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getLong(TURN_KEY, 0)
        val photos = widgetPhotos(pool, turn + widgetId, 4).mapIndexedNotNull { i, m ->
            val px = if (i == 0) WIDGET_THUMB_PX else CELL_PX
            // A photo deleted a moment ago has no thumbnail: show the others
            runCatching { context.contentResolver.loadThumbnail(m.uri, Size(px, px), null) }.getOrNull()
        }
        return PhotoShown(album.title, null, photos, open)
    }

    private suspend fun peopleNow(): List<PersonAlbum> = withTimeoutOrNull(PEOPLE_MS) { People.albums.first { it.isNotEmpty() } } ?: People.albums.value

    private suspend fun collectionsNow(albumId: Long, media: List<Media>): List<CollectionAlbum> {
        if (collectionIdOf(albumId) == null) return emptyList()
        val members = membersOf(Collections.items.first(), media) { it.id }
        return Collections.list.first().map { CollectionAlbum(it, members[it.id].orEmpty()) }
    }

    @Composable
    private fun Content(shown: PhotoShown, colors: WidgetColors) {
        val card = GlanceModifier.glanceFillMaxSize()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(colors.surface)
            .clickable(actionStartActivity(shown.open))
        val photos = shown.photos
        if (photos.isEmpty()) {
            GlanceColumn(card.glancePadding(16.dp), verticalAlignment = GlanceAlignment.Bottom) {
                GlanceText(shown.title, style = TextStyle(colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                shown.line?.let { GlanceText(it, style = TextStyle(colors.inkMuted, fontSize = 12.sp)) }
            }
            return
        }
        val fill = GlanceModifier.glanceFillMaxSize()
        GlanceBox(card, contentAlignment = GlanceAlignment.BottomStart) {
            // Responsive: the size is the layout Android picked for the space, one of the four above
            val size = LocalSize.current
            when {
                size == Large && photos.size >= 4 -> GlanceColumn(fill) {
                    PhotoRow(photos.subList(0, 2), GlanceModifier.defaultWeight().glanceFillMaxWidth())
                    Spacer(GlanceModifier.height(Gutter))
                    PhotoRow(photos.subList(2, 4), GlanceModifier.defaultWeight().glanceFillMaxWidth())
                }
                size.width > size.height && photos.size >= 2 -> PhotoRow(photos.take(2), fill)
                size.height > size.width && photos.size >= 2 -> GlanceColumn(fill) {
                    Photo(photos[0], GlanceModifier.defaultWeight().glanceFillMaxWidth())
                    Spacer(GlanceModifier.height(Gutter))
                    Photo(photos[1], GlanceModifier.defaultWeight().glanceFillMaxWidth())
                }
                else -> Photo(photos[0], fill)
            }
            Image(ImageProvider(R.drawable.widget_scrim), null, fill, contentScale = ContentScale.FillBounds)
            GlanceText(
                shown.title, GlanceModifier.glancePadding(16.dp),
                style = TextStyle(ColorProvider(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1,
            )
        }
    }

    @Composable
    private fun PhotoRow(photos: List<Bitmap>, modifier: GlanceModifier) = GlanceRow(modifier) {
        photos.forEachIndexed { i, photo ->
            if (i > 0) Spacer(GlanceModifier.width(Gutter))
            Photo(photo, GlanceModifier.defaultWeight().fillMaxHeight())
        }
    }

    // The title says what the widget shows, so TalkBack needn't hear each photo
    @Composable
    private fun Photo(photo: Bitmap, modifier: GlanceModifier) = Image(ImageProvider(photo), null, modifier, contentScale = ContentScale.Crop)
}

class PersonWidget : PhotoWidget(WidgetKind.Person)
class AlbumWidget : PhotoWidget(WidgetKind.Album)
class FavouritesWidget : PhotoWidget(WidgetKind.Favourites)

/** Puts a photo widget on the home screen and keeps the photos taking turns while any is placed. */
abstract class PhotoWidgetReceiver(widget: PhotoWidget) : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = widget

    // Called when a widget is placed or chosen again, and after a restart. KEEP: another widget doesn't move the turns.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val request = PeriodicWorkRequestBuilder<PhotoWidgetWorker>(ROTATE_HOURS, TimeUnit.HOURS)
            .setInitialDelay(ROTATE_HOURS, TimeUnit.HOURS) // it was just drawn
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()) // new photos can wait for a charge
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(ROTATE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit { appWidgetIds.forEach { remove(ALBUM_KEY + it) } }
    }

    // When the last of this kind goes: the turns stop once no photo widget of any kind is left
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        val manager = AppWidgetManager.getInstance(context)
        if (receivers.values.all { manager.getAppWidgetIds(ComponentName(context, it)).isEmpty() }) {
            WorkManager.getInstance(context).cancelUniqueWork(ROTATE_WORK)
        }
    }
}

class PersonWidgetReceiver : PhotoWidgetReceiver(PersonWidget())
class AlbumWidgetReceiver : PhotoWidgetReceiver(AlbumWidget())
class FavouritesWidgetReceiver : PhotoWidgetReceiver(FavouritesWidget())

/** Moves every photo widget on to its next photos. */
class PhotoWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit { putLong(TURN_KEY, prefs.getLong(TURN_KEY, 0) + 1) }
        PhotoWidget.updateAll(applicationContext)
        return Result.success()
    }
}

// ---------- Choosing what a widget shows ----------

/**
 * Where a person or album widget is told what to show: as it's placed, from the launcher's Reconfigure (Android 12+),
 * and from the widget itself once its album has gone. Backing out of placing one takes it off the home screen again, as
 * Android expects. Favourites has nothing to choose, so it never opens this.
 */
class WidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        // Exported for the launcher, so only ever about one of Album's own person or album widgets
        val kind = widgetKindOf(this, widgetId)?.takeIf { it != WidgetKind.Favourites } ?: return finish()
        enableEdgeToEdge()
        AlbumPrefs.load(this)
        AppearancePrefs.load(this)
        val current = widgetAlbum(this, widgetId)
        setContent {
            val appearance by AppearancePrefs.appearance.collectAsState()
            // Status bar icons follow Album's theme, as in MainActivity
            val dark = resolvedTheme(appearance.theme) != ThemeMode.Light
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            AlbumTheme(appearance) { WidgetChooser(kind, current, { pick(kind, widgetId, it) }, ::finish) }
        }
    }

    private fun pick(kind: WidgetKind, widgetId: Int, albumId: Long) {
        CrashLog.note("widget set: ${kind.name}")
        getSharedPreferences("settings", MODE_PRIVATE).edit { putLong(ALBUM_KEY + widgetId, albumId) }
        // Through the receiver, so Glance draws it and the turns start: Android doesn't update a widget it placed through
        // a configuration screen until that screen asks
        sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE, null, this, receivers.getValue(kind))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId)),
        )
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

/**
 * The chooser: everyone named in People, or every collection and album, each with its photo and count; [current] is
 * marked. Tapping one sets the widget and closes. Hidden items are left out, so a widget is never set to one.
 */
@Composable
private fun WidgetChooser(kind: WidgetKind, current: Long?, onPick: (Long) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val access = remember { canReadPhotos(context) }
    LaunchedEffect(access) { if (access) Gallery.start(context) }
    val all by Gallery.media.collectAsState()
    val hidden by Hidden.state.collectAsState()
    // Null until both have loaded, so nothing in Hidden items ever shows here for a moment
    val media = remember(all, hidden) { hidden?.let { h -> all?.withoutHidden(h) } }
    val people = if (kind == WidgetKind.Person) rememberPeopleToChoose() else null
    val collections = rememberCollections(media.orEmpty()).filter { it.items.any { m -> !m.isVideo } }
    val albums = remember(media) {
        media?.let { m ->
            val withPhotos = m.filter { !it.isVideo }.mapTo(HashSet()) { it.bucketId }
            albumsOf(m).filter { it.id in withPhotos }
        }
    }

    LazyColumn(Modifier.fillMaxSize().background(c.canvas), contentPadding = WindowInsets.systemBars.asPaddingValues()) {
        item(key = "title") {
            LargeTopBar(if (kind == WidgetKind.Person) "Choose a person" else "Choose an album", onBack = onClose)
            Text(
                if (kind == WidgetKind.Person) "Their newest photos take turns on your home screen, a few times a day."
                else "Its photos take turns on your home screen, a few times a day.",
                Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s4),
                style = AlbumTheme.type.body, color = c.inkMuted,
            )
        }
        when {
            !access -> item(key = "access") {
                EmptyState(Sym.PhotoLibrary, "See your photos here", "Open Album and allow access to your photos first.") {
                    AlbumButton("Open Album", {
                        context.startActivity(Intent(context, MainActivity::class.java))
                        onClose()
                    }, variant = ButtonVariant.Primary)
                }
            }
            kind == WidgetKind.Person -> {
                if (people?.isEmpty() == true) item(key = "none") {
                    EmptyState(Sym.Person, "No one to choose yet", "Name people under People in Collections, then choose one here.")
                }
                items(people.orEmpty(), key = { it.cluster }) { p ->
                    ChoiceRow(p.name.orEmpty(), counted(p.items.size, "photo", "photos"), p.albumId == current, { onPick(p.albumId) }) {
                        FaceImage(p.cover, it)
                    }
                }
            }
            else -> {
                if (collections.isNotEmpty()) {
                    item(key = "collections") { SectionHead("Collections") }
                    items(collections, key = { "c${it.albumId}" }) { col ->
                        ChoiceRow(col.name, itemCount(col.items.size), col.albumId == current, { onPick(col.albumId) }) {
                            Thumbnail(col.items.first(), it.clip(RoundedCornerShape(Space.radiusMd)))
                        }
                    }
                }
                if (albums?.isEmpty() == true && collections.isEmpty()) item(key = "none") {
                    EmptyState(Sym.PhotoAlbum, "No albums yet", "Albums with photos show up here.")
                }
                if (!albums.isNullOrEmpty()) {
                    item(key = "albums") { SectionHead("Albums") }
                    items(albums, key = { "a${it.id}" }) { a ->
                        ChoiceRow(a.name, itemCount(a.count), a.id == current, { onPick(a.id) }) {
                            Thumbnail(a.cover, it.clip(RoundedCornerShape(Space.radiusMd)))
                        }
                    }
                }
            }
        }
    }
}

/** Everyone a person widget can show, most photos first: named, in People, not in Hidden items. Null while People loads. */
@Composable
private fun rememberPeopleToChoose(): List<PersonAlbum>? = produceState<List<PersonAlbum>?>(null) {
    // An empty list only counts once the face index has had a moment to be read
    withTimeoutOrNull(PEOPLE_MS) { People.albums.first { it.isNotEmpty() } }
    People.albums.collect { all ->
        value = all.filter { it.name != null && !it.hidden && !it.locked && it.items.any { m -> !m.isVideo } }.sortedByDescending { it.items.size }
    }
}.value

/**
 * A row to choose: [image] (48dp), [title] over [sub], and when [chosen] a check on flash-soft, as Settings marks a
 * choice. The check carries the choice; the tint only backs it up.
 */
@Composable
private fun ChoiceRow(title: String, sub: String, chosen: Boolean, onClick: () -> Unit, image: @Composable (Modifier) -> Unit) {
    val c = AlbumTheme.colors
    Row(
        Modifier.padding(horizontal = Space.s3).fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(Space.radiusMd))
            .then(if (chosen) Modifier.background(c.flashSoft) else Modifier)
            .selectable(chosen, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Space.s1, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        image(Modifier.size(Space.s12))
        Column(Modifier.weight(1f)) {
            Text(title, style = AlbumTheme.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = AlbumTheme.type.caption, color = c.inkMuted)
        }
        if (chosen) Icon(Sym.Check, null, tint = c.flashInk)
    }
}
