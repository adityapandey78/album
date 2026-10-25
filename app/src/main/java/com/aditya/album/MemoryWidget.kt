package com.aditya.album

import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import androidx.glance.color.ColorProvider as DayNight

// The Memories home-screen widget: one photo from On this day (else a recent trip or a favourite) under its eyebrow and
// title. It updates once a day, just after midnight, and opens Album when tapped.

private const val WIDGET_THUMB_PX = 512 // bigger than a 2 x 2 widget on any phone; the full photo is never decoded
private const val LOAD_MS = 20_000L // the Library and the index's towns; a widget left showing nothing beats a hung update
private const val UPDATE_WORK = "memory-widget"

/**
 * The widget's photo and its memory: On this day, else the newest trip of the past year, else a favourite, a different
 * one each day. Within a memory a favourite beats the cover. [photos] newest first.
 */
fun <T> widgetPick(photos: List<T>, today: LocalDate, dayOf: (T) -> Long, isFavorite: (T) -> Boolean, placeOf: (T) -> Place?): Pair<Memory<T>, T>? {
    val yearAgo = today.minusYears(1).toEpochDay()
    val memory = onThisDay(photos, today, dayOf)
        ?: trips(photos, dayOf, placeOf).firstOrNull()?.takeIf { dayOf(it.items.last()) >= yearAgo }
    if (memory != null) return memory to (memory.items.firstOrNull(isFavorite) ?: memory.items.first())
    val favourites = photos.filter(isFavorite).ifEmpty { return null }
    val photo = favourites[(today.toEpochDay() % favourites.size).toInt()]
    val day = dayOf(photo)
    return Memory("Favourite · ${LocalDate.ofEpochDay(day).year}", dayRange(day, day), listOf(photo)) to photo
}

private class Shown(val eyebrow: String, val title: String, val photo: Bitmap)

private class WidgetColors(val surface: ColorProvider, val ink: ColorProvider, val inkMuted: ColorProvider)

class MemoryWidget : GlanceAppWidget() {
    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Redraws the widget now, for when the photo it shows may have left the Library (moved to the safe, trashed).
         * Cheap when no widget is placed: Glance has nothing to update.
         */
        fun refresh(context: Context) {
            val app = context.applicationContext
            scope.launch { runCatching { MemoryWidget().updateAll(app) }.onFailure { CrashLog.note("memory widget refresh: $it") } }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (shown, colors) = withContext(Dispatchers.IO) {
            runCatching { load(context) }.onFailure { CrashLog.note("memory widget: $it") }.getOrNull() to colorsOf(context)
        }
        provideContent { Content(shown, colors) }
    }

    /** The memory to show, or null without photo access or anything to show. */
    private suspend fun load(context: Context): Shown? {
        val granted = listOf(READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED).any {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return null
        // The app may not be open: start the photo list and read the folders hidden from the Library, as the app does
        AlbumPrefs.load(context)
        withContext(Dispatchers.Main) { Gallery.start(context) }
        val media = withTimeoutOrNull(LOAD_MS) { Gallery.media.filterNotNull().first() } ?: return null
        val photos = media.inPhotos(AlbumPrefs.hidden.value).filter { !it.isVideo }
        val placeOf = placesById(withTimeoutOrNull(LOAD_MS) { Explore.places.filterNotNull().first() }.orEmpty())
        val (memory, photo) = widgetPick(photos, LocalDate.now(), Media::day, Media::isFavorite) { placeOf[it.id] } ?: return null
        val thumb = context.contentResolver.loadThumbnail(photo.uri, Size(WIDGET_THUMB_PX, WIDGET_THUMB_PX), null)
        return Shown(memory.eyebrow, memory.title, thumb)
    }

    /** Album's own theme setting: System follows the phone's dark theme, the others stay as chosen. */
    private fun colorsOf(context: Context): WidgetColors {
        AppearancePrefs.load(context)
        val theme = AppearancePrefs.appearance.value.theme
        fun role(of: (AlbumColors) -> Color) = when (theme) {
            ThemeMode.System -> DayNight(day = of(albumColors(ThemeMode.Light, Accent.Lime)), night = of(albumColors(ThemeMode.Dark, Accent.Lime)))
            else -> ColorProvider(of(albumColors(theme, Accent.Lime)))
        }
        return WidgetColors(role { it.surface }, role { it.ink }, role { it.inkMuted })
    }

    @Composable
    private fun Content(shown: Shown?, colors: WidgetColors) {
        val card = GlanceModifier.fillMaxSize()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(colors.surface)
            .clickable(actionStartActivity<MainActivity>())
        if (shown == null) {
            Column(card.padding(16.dp), verticalAlignment = Alignment.Bottom) {
                Text("Memories", style = TextStyle(colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium))
                Text("Photos from this day in earlier years show up here.", style = TextStyle(colors.inkMuted, fontSize = 12.sp))
            }
            return
        }
        val onPhoto = ColorProvider(Color.White)
        Box(card, contentAlignment = Alignment.BottomStart) {
            Image(ImageProvider(shown.photo), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Image(ImageProvider(R.drawable.widget_scrim), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Column(GlanceModifier.padding(16.dp)) {
                Text(shown.eyebrow, style = TextStyle(onPhoto, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Text(shown.title, style = TextStyle(onPhoto, fontSize = 22.sp, fontWeight = FontWeight.Bold), maxLines = 2)
            }
        }
    }
}

/** Puts [MemoryWidget] on the home screen and keeps a daily update going while any is placed. */
class MemoryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemoryWidget()

    // Called when a widget is placed and after a restart. KEEP: a second widget doesn't move the update.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Just after midnight, so On this day turns over with the date. WorkManager waits for the phone to be awake
        // anyway, which a widget's own update period wouldn't.
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).plusMinutes(1)
        val request = PeriodicWorkRequestBuilder<MemoryWidgetWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(ZonedDateTime.now(zone), midnight))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UPDATE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(UPDATE_WORK)
    }
}

/** The daily update of every Memories widget. */
class MemoryWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        MemoryWidget().updateAll(applicationContext)
        return Result.success()
    }
}
